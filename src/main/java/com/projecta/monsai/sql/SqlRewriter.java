package com.projecta.monsai.sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import com.projecta.monsai.sql.SqlFragment.FragmentType;

import mondrian.rolap.RolapUtil;
import mondrian.util.Pair;

/**
 * The SqlRewriter knows the types of queries generated by Mondrian, and tries
 * to fix some known problems:
 *
 * - When using sum() or count(distinct) on a field of type hll, the aggregation
 *   is replaced by a call to hll_cardinality()
 *
 * - When using a constraint on a joined dimension table, the fact table will be
 *   constraint as well, to help the postgres execution planner
 */
public class SqlRewriter {

    // query information
    private List<SqlFragment>        fragments;
    private Map<String, SqlFragment> aliases;
    private Map<String, SqlFragment> joins;
    private boolean                  modified;

    // database caches
    static volatile Set<String>                   hyperLogLogColumnsCache = null;
    static volatile Map<String, Boolean>          dimensionTablesCache    = new HashMap<>();
    static volatile Map<String, Pair<Long, Long>> dimensionRangesCache    = new HashMap<>();
    static volatile long                          dimensionRangesCacheTTL = 0;

    private static final int MAX_DIM_TABLE_SIZE                    = 10000;
    private static final int DIMENSION_RANGES_CACHE_TIMEOUT_MILLIS = 10000; // 10 seconds

    // parser constants
    static final Map<String, FragmentType> KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put("select",   FragmentType.SELECT_KEWORD);
        KEYWORDS.put("from",     FragmentType.FROM_KEWORD);
        KEYWORDS.put("where",    FragmentType.WHERE_KEWORD);
        KEYWORDS.put("and",      FragmentType.AND_KEWORD);
        KEYWORDS.put("group by", FragmentType.GROUP_BY_KEWORD);
        KEYWORDS.put("order by", FragmentType.ORDER_BY_KEWORD);
    }

    private static final Pattern TABLE_PATTERN              = Pattern.compile("\"(\\w+)\".\"(\\w+)\" as \"(\\w+)\",?");
    private static final Pattern SELECT_AGGREGATION_PATTERN = Pattern.compile("([\\w (]+)\"(\\w+)\".\"(\\w+)\"\\) as \"(\\w+)\"(,)?");

    private static final Pattern JOIN_CONDITION_PATTERN     = Pattern.compile("\"(\\w+)\".\"(\\w+)\" = \"(\\w+)\".\"(\\w+)\"");
    private static final Pattern EQUALS_CONDITION_PATTERN   = Pattern.compile("\"(\\w+)\".\"(\\w+)\" = [\\w' ]+");
    private static final Pattern IN_CONDITION_PATTERN       = Pattern.compile("\"(\\w+)\".\"(\\w+)\" in \\([\\w', ]+\\)");

    private static final String NL = System.getProperty("line.separator");
    private static final Logger LOG = Logger.getLogger( SqlRewriter.class );


    /**
     * Rewrites the sql query
     */
    public String rewrite(String sql, Connection con) {

        try {
            // initialize
            fragments = new ArrayList<>();
            aliases   = new HashMap<>();
            joins     = new HashMap<>();
            modified  = false;

            // parse the query
            parseQuery(sql);

            // rewrite specific parts of the query
            replaceHllDistinctCount(con);

            if (!modified) {
                return sql;
            }

            // generate the modified sql query
            StringBuilder result = new StringBuilder();
            for (SqlFragment fragment : fragments) {
                result.append(fragment.getNewText() != null ? fragment.getNewText() : fragment.getText());
                result.append(NL);
            }

            RolapUtil.SQL_LOGGER.debug("rewritten to:\n" + result);
            return result.toString();
        }
        catch (Throwable e) {
            LOG.error("Error in SqlRewriter.rewrite()", e);
            return sql;
        }
    }


    /**
     * parses the query by applying regular expressions to each line
     */
    private void parseQuery(String sql) {

        FragmentType lastKeyword = null;

        // parse the query line by line
        for (String text : sql.split(NL)) {

            SqlFragment fragment = new SqlFragment(text);
            fragments.add(fragment);

            // check for keywords (they are expected to appear alone on the line)
            FragmentType type = KEYWORDS.get(text);
            if (type != null) {
                fragment.setType(type);
                lastKeyword = type;
                continue;
            }

            text = text.trim();

            // parse SELECT expressions
            if (lastKeyword == FragmentType.SELECT_KEWORD) {

                Matcher aggregationExpr = SELECT_AGGREGATION_PATTERN.matcher(text);
                if (aggregationExpr.matches()) {
                    fragment.setType(FragmentType.SELECT_AGGREGATION);
                    fragment.setAggFunction(aggregationExpr.group(1).trim());
                    fragment.setTableAlias(aggregationExpr.group(2));
                    fragment.setColumnName(aggregationExpr.group(3));
                    fragment.setResultAlias(aggregationExpr.group(4));
                    fragment.setHasNext(aggregationExpr.group(5) != null);
                    aliases.put(fragment.getTableAlias(), fragment);
                }
            }

            // parse FROM expressions
            if (lastKeyword == FragmentType.FROM_KEWORD) {

                Matcher tableExpr = TABLE_PATTERN.matcher(text);
                if (tableExpr.matches()) {
                    fragment.setType(FragmentType.TABLE);
                    fragment.setSchemaName(tableExpr.group(1));
                    fragment.setTableName(tableExpr.group(2));
                    fragment.setTableAlias(tableExpr.group(3));
                    aliases.put(fragment.getTableAlias(), fragment);
                }
            }

            // parse WHERE expressions
            if (lastKeyword == FragmentType.WHERE_KEWORD || lastKeyword == FragmentType.AND_KEWORD) {

                Matcher joinExpr = JOIN_CONDITION_PATTERN.matcher(text);
                if (joinExpr.matches()) {
                    fragment.setType(FragmentType.JOIN_CONDITION);
                    fragment.setTableAlias(joinExpr.group(1));
                    fragment.setColumnName(joinExpr.group(2));
                    fragment.setJoinedTableAlias(joinExpr.group(3));
                    fragment.setJoinedColumnName(joinExpr.group(4));
                    joins.put(fragment.getJoinedTableAlias(), fragment);
                }

                // sometimes conditions are in brackets
                if (text.startsWith("(") && text.endsWith(")")) {
                    text = text.substring(1, text.length() - 1);
                }

                Matcher conditionExpr = EQUALS_CONDITION_PATTERN.matcher(text);
                if (!conditionExpr.matches()) {
                    conditionExpr = IN_CONDITION_PATTERN.matcher(text);
                }

                if (conditionExpr.matches()) {
                    fragment.setType(FragmentType.CONDITION);
                    fragment.setTableAlias(conditionExpr.group(1));
                    fragment.setColumnName(conditionExpr.group(2));
                }
              }
        }
    }


    // ------------------------------------------------------------------------
    // Replacement functions
    // ------------------------------------------------------------------------


    /**
     * Checks for distinct counts on columns that are of type hyperloglog
     */
    private void replaceHllDistinctCount(Connection con) {

        Set<String> hllColumns = getHyperLogLogColumns(con);

        for (SqlFragment fragment : fragments) {
            // check sum and count distinct expressions
            if (fragment.getType() == FragmentType.SELECT_AGGREGATION
                    && (fragment.getAggFunction().equals("count(distinct")
                        || fragment.getAggFunction().equals( "sum(" ))) {

                // check if the aggregated column is of type hll
                SqlFragment table = aliases.get(fragment.getTableAlias());
                if (table == null) {
                    continue;
                }

                String fullColumnName = (table.getSchemaName() + "." + table.getTableName() + "." + fragment.getColumnName()).toLowerCase();
                if (hllColumns.contains(fullColumnName)) {
                    // rewrite using hll functions
                    fragment.setNewText(
                            "    hll_cardinality(hll_union_agg("
                                    + "\"" + fragment.getTableAlias() + "\".\"" + fragment.getColumnName() + "\""
                                    + ")) as \"" + fragment.getResultAlias() + "\""
                                    + (fragment.hasNext() ? "," : "" ) );
                    modified = true;
                }
            }
        }
    }


    // ------------------------------------------------------------------------
    // Cached database access methods
    // ------------------------------------------------------------------------

    /**
     * Clears the internal caches
     */
    public static synchronized void clearCache() {

        hyperLogLogColumnsCache = null;
    }


    /**
     * Retrieves all columns of type hll from the database.
     */
    private static synchronized Set<String> getHyperLogLogColumns(Connection con) {

        // check the cache
        if (hyperLogLogColumnsCache != null) {
            return hyperLogLogColumnsCache;
        }

        // retrieve all hll columns from the database
        String query = "\nselect lower(n.nspname || '.'  || c.relname || '.' || a.attname) AS hll_column\n"
                     + "from pg_attribute a\n"
                     + "join pg_class c on a.attrelid = c.oid\n"
                     + "join pg_namespace n ON c.relnamespace = n.oid\n"
                     + "join pg_type t on a.atttypid = t.oid\n"
                     + "where typname = 'hll'";

        RolapUtil.SQL_LOGGER.debug(query);

        try {
            Set<String> columns = new HashSet<>();
            try (Statement stmt = con.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
            hyperLogLogColumnsCache = columns;
            return columns;
        }
        catch (Throwable e) {
            LOG.error("Error in SqlRewriter.getHyperLogLogColumns()", e);
            return Collections.emptySet();
        }
    }
}

