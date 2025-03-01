package com.unciv.logic.map.mapunit

import com.unciv.UncivGame
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType

class UnitTurnManager(val unit: MapUnit) {

    fun endTurn() {
        unit.movement.clearPathfindingCache()
        if (unit.currentMovement > 0
                && unit.getTile().improvementInProgress != null
                && unit.canBuildImprovement(unit.getTile().getTileImprovementInProgress()!!)
        ) workOnImprovement()
        if (unit.currentMovement == unit.getMaxMovement().toFloat() && unit.isFortified() && unit.turnsFortified < 2) {
            unit.turnsFortified++
        }
        if (!unit.isFortified())
            unit.turnsFortified = 0

        if (unit.currentMovement == unit.getMaxMovement().toFloat() // didn't move this turn
                || unit.hasUnique(UniqueType.HealsEvenAfterAction)
        ) healUnit()

        if (unit.action != null && unit.health > 99)
            if (unit.isActionUntilHealed()) {
                unit.action = null // wake up when healed
            }

        if (unit.isPreparingParadrop() || unit.isPreparingAirSweep())
            unit.action = null

        if (unit.hasUnique(UniqueType.ReligiousUnit)
                && unit.getTile().getOwner() != null
                && !unit.getTile().getOwner()!!.isCityState()
                && !unit.civ.diplomacyFunctions.canPassThroughTiles(unit.getTile().getOwner()!!)
        ) {
            val lostReligiousStrength =
                    unit.getMatchingUniques(UniqueType.CanEnterForeignTilesButLosesReligiousStrength)
                        .map { it.params[0].toInt() }
                        .minOrNull()
            if (lostReligiousStrength != null)
                unit.religiousStrengthLost += lostReligiousStrength
            if (unit.religiousStrengthLost >= unit.baseUnit.religiousStrength) {
                unit.civ.addNotification("Your [${unit.name}] lost its faith after spending too long inside enemy territory!",
                    unit.getTile().position, NotificationCategory.Units, unit.name)
                unit.destroy()
            }
        }

        doCitadelDamage()
        doTerrainDamage()

        unit.addMovementMemory()

        for (unique in unit.getTriggeredUniques(UniqueType.TriggerUponEndingTurnInTile))
            if (unique.conditionals.any { it.type == UniqueType.TriggerUponEndingTurnInTile
                            && unit.getTile().matchesFilter(it.params[0], unit.civ) })
                UniqueTriggerActivation.triggerUnitwideUnique(unique, unit)
    }


    private fun healUnit() {
        val amountToHealBy = unit.getHealAmountForCurrentTile()
        if (amountToHealBy == 0) return

        unit.healBy(amountToHealBy)
    }


    private fun doCitadelDamage() {
        // Check for Citadel damage - note: 'Damage does not stack with other Citadels'
        val (citadelTile, damage) = unit.currentTile.neighbors
            .filter {
                it.getOwner() != null
                        && it.getUnpillagedImprovement() != null
                        && unit.civ.isAtWarWith(it.getOwner()!!)
            }.map { tile ->
                tile to tile.getTileImprovement()!!.getMatchingUniques(UniqueType.DamagesAdjacentEnemyUnits)
                    .sumOf { it.params[0].toInt() }
            }.maxByOrNull { it.second }
            ?: return
        if (damage == 0) return
        unit.health -= damage
        val locations = LocationAction(citadelTile.position, unit.currentTile.position)
        if (unit.health <= 0) {
            unit.civ.addNotification(
                "An enemy [Citadel] has destroyed our [${unit.name}]",
                locations,
                NotificationCategory.War,
                NotificationIcon.Citadel, NotificationIcon.Death, unit.name
            )
            citadelTile.getOwner()?.addNotification(
                "Your [Citadel] has destroyed an enemy [${unit.name}]",
                locations,
                NotificationCategory.War,
                NotificationIcon.Citadel, NotificationIcon.Death, unit.name
            )
            unit.destroy()
        } else unit.civ.addNotification(
            "An enemy [Citadel] has attacked our [${unit.name}]",
            locations,
            NotificationCategory.War,
            NotificationIcon.Citadel, NotificationIcon.War, unit.name
        )
    }


    private fun doTerrainDamage() {
        val tileDamage = unit.getDamageFromTerrain()
        unit.health -= tileDamage

        if (unit.health <= 0) {
            unit.civ.addNotification(
                "Our [${unit.name}] took [$tileDamage] tile damage and was destroyed",
                unit.currentTile.position,
                NotificationCategory.Units,
                unit.name,
                NotificationIcon.Death
            )
            unit.destroy()
        } else if (tileDamage > 0) unit.civ.addNotification(
            "Our [${unit.name}] took [$tileDamage] tile damage",
            unit.currentTile.position,
            NotificationCategory.Units,
            unit.name
        )
    }


    fun startTurn() {
        unit.movement.clearPathfindingCache()
        unit.currentMovement = unit.getMaxMovement().toFloat()
        unit.attacksThisTurn = 0
        unit.due = true

        // Hakkapeliitta movement boost
        // For every double-stacked tile, check if our cohabitant can boost our speed
        // (a test `count() > 1` is no optimization - two iterations of a sequence instead of one)
        for (boostingUnit in unit.getTile().getUnits()) {
            if (boostingUnit == unit) continue

            if (boostingUnit.getMatchingUniques(UniqueType.TransferMovement)
                        .none { unit.matchesFilter(it.params[0]) } ) continue
            unit.currentMovement = unit.currentMovement.coerceAtLeast(boostingUnit.getMaxMovement().toFloat())
        }

        // Wake sleeping units if there's an enemy in vision range:
        // Military units always but civilians only if not protected.
        if (unit.isSleeping() && (unit.isMilitary() || (unit.currentTile.militaryUnit == null && !unit.currentTile.isCityCenter())) &&
                unit.currentTile.getTilesInDistance(3).any {
                    it.militaryUnit != null && it in unit.civ.viewableTiles && it.militaryUnit!!.civ.isAtWarWith(unit.civ)
                }
        )  unit.action = null

        val tileOwner = unit.getTile().getOwner()
        if (tileOwner != null
                && !unit.cache.canEnterForeignTerrain
                && !unit.civ.diplomacyFunctions.canPassThroughTiles(tileOwner)
                && !tileOwner.isCityState()) // if an enemy city expanded onto this tile while I was in it
            unit.movement.teleportToClosestMoveableTile()

        unit.addMovementMemory()
        unit.attacksSinceTurnStart.clear()
    }

    private fun workOnImprovement() {
        val tile = unit.getTile()
        if (tile.isMarkedForCreatesOneImprovement()) return
        tile.turnsToImprovement -= 1
        if (tile.turnsToImprovement != 0) return

        if (unit.civ.isCurrentPlayer())
            UncivGame.Current.settings.addCompletedTutorialTask("Construct an improvement")

        val improvementInProgress = tile.improvementInProgress ?: return
        tile.changeImprovement(improvementInProgress, unit.civ)

        tile.improvementInProgress = null
        tile.getCity()?.updateCitizens = true
    }



}
