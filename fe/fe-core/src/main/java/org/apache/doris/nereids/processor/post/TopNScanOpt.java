// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.processor.post;

import org.apache.doris.nereids.CascadesContext;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.SortPhase;
import org.apache.doris.nereids.trees.plans.algebra.OlapScan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalCatalogRelation;
import org.apache.doris.nereids.trees.plans.physical.PhysicalDeferMaterializeTopN;
import org.apache.doris.nereids.trees.plans.physical.PhysicalTopN;
import org.apache.doris.nereids.trees.plans.physical.PhysicalWindow;
import org.apache.doris.qe.ConnectContext;

import java.util.Optional;
/**
 * topN opt
 * refer to:
 * <a href="https://github.com/apache/doris/pull/15558">...</a>
 * <a href="https://github.com/apache/doris/pull/15663">...</a>
 *
 * // [deprecated] only support simple case: select ... from tbl [where ...] order by ... limit ...
 */

public class TopNScanOpt extends PlanPostProcessor {

    @Override
    public PhysicalTopN<? extends Plan> visitPhysicalTopN(PhysicalTopN<? extends Plan> topN, CascadesContext ctx) {
        Optional<OlapScan> scanOpt = findScanForTopnFilter(topN);
        scanOpt.ifPresent(scan -> ctx.getTopnFilterContext().addTopnFilter(topN, scan));
        topN.child().accept(this, ctx);
        return topN;
    }

    @Override
    public Plan visitPhysicalDeferMaterializeTopN(PhysicalDeferMaterializeTopN<? extends Plan> topN,
            CascadesContext context) {
        Optional<OlapScan> scanOpt = findScanForTopnFilter(topN.getPhysicalTopN());
        scanOpt.ifPresent(scan -> context.getTopnFilterContext().addTopnFilter(topN, scan));
        topN.child().accept(this, context);
        return topN;
    }

    private Optional<OlapScan> findScanForTopnFilter(PhysicalTopN<? extends Plan> topN) {
        if (topN.getSortPhase() != SortPhase.LOCAL_SORT) {
            return Optional.empty();
        }
        if (topN.getOrderKeys().isEmpty()) {
            return Optional.empty();
        }

        // topn opt
        long topNOptLimitThreshold = getTopNOptLimitThreshold();
        if (topNOptLimitThreshold == -1 || topN.getLimit() > topNOptLimitThreshold) {
            return Optional.empty();
        }
        // if firstKey's column is not present, it means the firstKey is not an original column from scan node
        // for example: "select cast(k1 as INT) as id from tbl1 order by id limit 2;" the firstKey "id" is
        // a cast expr which is not from tbl1 and its column is not present.
        // On the other hand "select k1 as id from tbl1 order by id limit 2;" the firstKey "id" is just an alias of k1
        // so its column is present which is valid for topN optimize
        // see Alias::toSlot() method to get how column info is passed around by alias of slotReference
        Expression firstKey = topN.getOrderKeys().get(0).getExpr();
        if (!firstKey.isColumnFromTable()) {
            return Optional.empty();
        }
        if (firstKey.getDataType().isFloatType()
                || firstKey.getDataType().isDoubleType()) {
            return Optional.empty();
        }

        if (! (firstKey instanceof SlotReference)) {
            return Optional.empty();
        }
        OlapScan olapScan = findScanNodeBySlotReference(topN, (SlotReference) firstKey);
        if (olapScan != null
                && olapScan.getTable().isDupKeysOrMergeOnWrite()
                && olapScan instanceof PhysicalCatalogRelation) {
            return Optional.of(olapScan);
        }

        return Optional.empty();
    }

    private OlapScan findScanNodeBySlotReference(Plan root, SlotReference slot) {
        OlapScan target = null;
        if (root instanceof OlapScan && root.getOutputSet().contains(slot)) {
            return (OlapScan) root;
        } else {
            if (! root.children().isEmpty()) {
                // for join and intersect, push topn-filter to their left child.
                // TODO for union, topn-filter can be pushed down to all of its children.
                Plan child = root.child(0);
                if (!(child instanceof PhysicalWindow) && child.getOutputSet().contains(slot)) {
                    target = findScanNodeBySlotReference(child, slot);
                    if (target != null) {
                        return target;
                    }
                }
            }
        }
        return target;
    }

    private long getTopNOptLimitThreshold() {
        if (ConnectContext.get() != null && ConnectContext.get().getSessionVariable() != null) {
            return ConnectContext.get().getSessionVariable().topnOptLimitThreshold;
        }
        return -1;
    }
}
