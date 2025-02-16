/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

suite("topn-filter") {
    String db = context.config.getDbNameByFile(new File(context.file.parent))
    sql "use ${db}"
    sql 'set enable_nereids_planner=true'
    sql 'set enable_fallback_to_original_planner=false'
    sql 'set disable_join_reorder=true;'
    sql 'set topn_opt_limit_threshold=1024'
    def String simpleTopn = """
        select *
        from orders
        order by o_orderkey
        limit 10;"""
    
    explain {
        sql "${simpleTopn}"
        contains "TOPN OPT:1"
    }

    qt_simpleTopn "${simpleTopn}"
    
    def String complexTopn = """
        select o_orderkey, c_custkey, n_nationkey
        from orders 
        join[broadcast] customer on o_custkey = c_custkey 
        join[broadcast] nation on c_nationkey=n_nationkey
        order by o_orderkey limit 2; 
        """
    explain{
        sql "${complexTopn}"
        contains "TOPN OPT:7"
    }
    qt_complexTopn "${complexTopn}"

    def multi_topn_asc = """
    select o_orderkey, c_custkey, n_nationkey
    from orders 
    join[broadcast] customer on o_custkey = c_custkey 
    join[broadcast] ( select * from nation order by n_nationkey asc limit 1) as n on c_nationkey=n_nationkey
    order by o_orderkey limit 2; 
    """
    qt_check_result "${multi_topn_asc}"
    explain{
        sql "${multi_topn_asc}"
        contains "TOPN OPT:9"
        contains "TOPN OPT:1"
    }

    def multi_topn_desc = """
    select o_orderkey, c_custkey, n_nationkey
    from orders 
    join[broadcast] customer on o_custkey = c_custkey 
    join[broadcast] (select * from nation order by n_nationkey desc limit 1) as n on c_nationkey=n_nationkey
    order by o_orderkey limit 2; 
    """
    explain {
        sql "${multi_topn_desc}"
        contains "TOPN OPT:9"
        contains "TOPN OPT:1"
    }

    qt_check_result2 "${multi_topn_desc}"

    // do not use topn-filter
    explain {
        sql """
                select o_orderkey, c_custkey
                from orders 
                join[broadcast] customer on o_custkey = c_custkey 
                order by c_custkey limit 2; 
            """
        notContains "TOPN OPT:"
    }

    // push topn filter down through AGG
    explain {
        sql """
            select s_nationkey, count(1) from supplier group by s_nationkey order by s_nationkey limit 1;
        """
        contains "TOPN OPT:"
    }

    // push topn filter down through AGG + Join
    explain {
        sql """
            select * 
            from 
             (select s_nationkey, count(1) as total from supplier group by s_nationkey having total > 10 ) T
            join nation on s_nationkey = n_nationkey 
            order by s_nationkey limit 1;
        """
        contains "TOPN OPT:"
    }

    explain {
        sql "select n_regionkey, sum(n_nationkey) from nation group by grouping sets((n_regionkey)) order by n_regionkey limit 2;"
        contains "TOPN OPT"
    }

    qt_groupingsets "select n_regionkey, sum(n_nationkey) from nation group by grouping sets((n_regionkey)) order by n_regionkey limit 2;"

    sql "set enable_pipeline_engine=false;"
    sql "set enable_pipeline_x_engine=false;"
    qt_groupingsets2 "select n_regionkey, sum(n_nationkey) from nation group by grouping sets((n_regionkey)) order by n_regionkey limit 2;"
}