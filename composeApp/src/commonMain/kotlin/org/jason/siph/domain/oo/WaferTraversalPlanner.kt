package org.jason.siph.domain.oo

import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.autonomy.SiPhDieDefinition
import org.jason.siph.domain.autonomy.SiPhWaferDefinition

/**
 * 纯领域 Wafer 导航规划器。
 *
 * 不发送任何运动命令，只把 Wafer/Die/Sub-Die/Coupler 定义转换成确定性的测量顺序。
 */
class WaferTraversalPlanner {

    fun buildRoute(
        wafer: SiPhWaferDefinition,
        strategy: WaferTraversalStrategy,
        explicitSiteOrder: List<MeasurementSiteKey> = emptyList(),
        selectedSites: Set<MeasurementSiteKey> = emptySet()
    ): List<MeasurementSiteKey> {
        val enabledSites = enabledSites(wafer)
        val selected = if (selectedSites.isEmpty()) {
            enabledSites
        } else {
            enabledSites.filterTo(linkedSetOf()) { it in selectedSites }
        }

        if (strategy == WaferTraversalStrategy.Explicit) {
            require(explicitSiteOrder.isNotEmpty()) {
                "explicit traversal requires explicitSiteOrder"
            }
            val available = selected.toSet()
            return explicitSiteOrder
                .distinct()
                .filter { it in available }
        }

        val enabledDies = wafer.dies.filter { it.enabled }
        val dieOrder = when (strategy) {
            WaferTraversalStrategy.RowMajor -> enabledDies.sortedWith(
                compareBy<SiPhDieDefinition> { it.index.row }
                    .thenBy { it.index.column }
            )

            WaferTraversalStrategy.ColumnMajor -> enabledDies.sortedWith(
                compareBy<SiPhDieDefinition> { it.index.column }
                    .thenBy { it.index.row }
            )

            WaferTraversalStrategy.Serpentine -> serpentineDies(enabledDies)
            WaferTraversalStrategy.Explicit -> error("handled above")
        }

        val selectedSet = selected.toSet()
        return buildList {
            dieOrder.forEach { die ->
                die.subDies
                    .asSequence()
                    .filter { it.enabled }
                    .forEach { subDie ->
                        subDie.couplers
                            .asSequence()
                            .filter { it.enabled }
                            .map { coupler ->
                                MeasurementSiteKey(
                                    waferId = wafer.id,
                                    die = die.index,
                                    subDieId = subDie.id,
                                    couplerId = coupler.id
                                )
                            }
                            .filter { it in selectedSet }
                            .forEach(::add)
                    }
            }
        }
    }

    private fun enabledSites(wafer: SiPhWaferDefinition): LinkedHashSet<MeasurementSiteKey> =
        buildSet {
            wafer.dies
                .asSequence()
                .filter { it.enabled }
                .forEach { die ->
                    die.subDies
                        .asSequence()
                        .filter { it.enabled }
                        .forEach { subDie ->
                            subDie.couplers
                                .asSequence()
                                .filter { it.enabled }
                                .forEach { coupler ->
                                    add(
                                        MeasurementSiteKey(
                                            waferId = wafer.id,
                                            die = die.index,
                                            subDieId = subDie.id,
                                            couplerId = coupler.id
                                        )
                                    )
                                }
                        }
                }
        }.toCollection(linkedSetOf())

    private fun serpentineDies(
        dies: List<SiPhDieDefinition>
    ): List<SiPhDieDefinition> {
        val rows = dies.groupBy { it.index.row }.toSortedMap()
        return buildList {
            rows.entries.forEachIndexed { rowIndex, (_, rowDies) ->
                val ordered = rowDies.sortedBy { it.index.column }
                addAll(if (rowIndex % 2 == 0) ordered else ordered.asReversed())
            }
        }
    }
}
