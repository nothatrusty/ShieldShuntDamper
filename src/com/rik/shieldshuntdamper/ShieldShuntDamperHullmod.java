package com.rik.shieldshuntdamper;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.input.Mouse;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.combat.listeners.DamageListener;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.impl.campaign.ids.Tags;

public class ShieldShuntDamperHullmod extends BaseHullMod {

    public static final float ARMOR_BONUS = 15f;
    public static final float SMOD_ARMOR_BONUS = 15f;

    private static final Map<String, String> ORIGINAL_DEFENSE_IDS = new HashMap<>();

    // Exact vanilla DamperFieldStats damage taken multipliers
    private static final Map<HullSize, Float> DAMAGE_MULT = new HashMap<>();
    static {
        DAMAGE_MULT.put(HullSize.FIGHTER, 0.33f);
        DAMAGE_MULT.put(HullSize.FRIGATE, 0.33f);
        DAMAGE_MULT.put(HullSize.DESTROYER, 0.33f);
        DAMAGE_MULT.put(HullSize.CRUISER, 0.50f);
        DAMAGE_MULT.put(HullSize.CAPITAL_SHIP, 0.50f);
    }

    // Exact vanilla ship_systems.csv & damper.system values
    public static final int MAX_CHARGES = 2;
    public static final float CHARGE_REGEN = 0.05f;
    public static final float CHARGE_UP = 0.25f;
    public static final float ACTIVE_DURATION = 3.0f;
    public static final float CHARGE_DOWN = 0.25f;

    public static final Color JITTER_COLOR = new Color(255, 165, 90, 55);
    public static final Color JITTER_UNDER_COLOR = new Color(255, 165, 90, 155);

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        boolean sMod = isSMod(stats);
        stats.getArmorBonus().modifyPercent(id, ARMOR_BONUS + (sMod ? SMOD_ARMOR_BONUS : 0f));

        // Show "Special: Damper Field" in the Refit screen
        if (stats.getVariant() != null && stats.getVariant().getHullSpec() != null) {
            ShipHullSpecAPI spec = stats.getVariant().getHullSpec();
            String hullId = spec.getHullId();
            if (!ORIGINAL_DEFENSE_IDS.containsKey(hullId)) {
                ORIGINAL_DEFENSE_IDS.put(hullId, spec.getShipDefenseId());
            }
            spec.setShipDefenseId("damper");
        }
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // Exact vanilla ShieldShunt shield removal (cleanly hides shield stats in Refit UI)
        ship.setShield(ShieldType.NONE, 0f, 1f, 1f);

        // Attach the 1:1 vanilla-spec Right-Click Damper Field combat listener
        if (!ship.hasListenerOfClass(RightClickDamperListener.class)) {
            ship.addListener(new RightClickDamperListener(ship));
        }
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship == null) return false;

        ShipHullSpecAPI spec = ship.getHullSpec();
        if (spec != null && !ship.getVariant().hasHullMod(HullMods.SHIELD_SHUNT) && ORIGINAL_DEFENSE_IDS.containsKey(spec.getHullId())) {
            spec.setShipDefenseId(ORIGINAL_DEFENSE_IDS.get(spec.getHullId()));
        }

        // 1. Must have a shield generator originally (cannot put on phase or shieldless ships)
        if (ship.getVariant().getHullSpec().getShieldType() == ShieldType.NONE) {
            return false;
        }

        // 2. Block if the ship already has a Damper Field system
        if (hasDamperSystem(ship.getSystem()) || hasDamperSystem(ship.getPhaseCloak())) {
            return false;
        }

        // 3. Incompatible with Makeshift Shield Generator (vanilla rule)
        if (ship.getVariant().hasHullMod(HullMods.MAKESHIFT_GENERATOR)) {
            return false;
        }

        // 4. Allow if already installed, otherwise require an active shield
        if (ship.getVariant().hasHullMod(HullMods.SHIELD_SHUNT)) {
            return true;
        }

        return ship.getShield() != null;
    }

    private boolean hasDamperSystem(ShipSystemAPI sys) {
        if (sys == null) return false;
        if (sys.getId().equals("damper") || sys.getId().equals("damper_omega")) return true;
        return sys.getSpecAPI() != null && sys.getSpecAPI().hasTag(Tags.SYSTEM_USES_DAMPER_FIELD_AI);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship == null) return "Incompatible with ship";

        if (ship.getVariant().hasHullMod(HullMods.MAKESHIFT_GENERATOR)) {
            return "Incompatible with Makeshift Shield Generator";
        }
        if (ship.getHullSpec().getDefenseType() == ShieldType.PHASE) {
            return "Cannot be installed on phase ships";
        }
        if (hasDamperSystem(ship.getSystem()) || hasDamperSystem(ship.getPhaseCloak())) {
            return "Ship already equipped with a Damper Field system";
        }

        return "Ship has no shields";
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int) ARMOR_BONUS + "%";
        return null;
    }

    @Override
    public String getSModDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) return "" + (int) SMOD_ARMOR_BONUS + "%";
        return null;
    }

    @Override
    public boolean hasSModEffect() {
        return true;
    }

    public static class RightClickDamperListener implements AdvanceableListener, DamageListener {
        private enum State { IDLE, CHARGEUP, ACTIVE, CHARGEDOWN }

        private final ShipAPI ship;
        private State state = State.IDLE;
        private float stateTimer = 0f;
        private int charges = MAX_CHARGES;
        private float regenProgress = 0f;
        private boolean rightClickWasPressed = false;
        private float recentDamageTaken = 0f;
        private float damageWindowTimer = 0f;
        private final String modId = "shield_shunt_damper_active";

        public RightClickDamperListener(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public void reportDamageApplied(Object source, CombatEntityAPI target, ApplyDamageResultAPI result) {
            if (target != ship || result == null) return;
            float dmg = result.getTotalDamageToArmor() + result.getDamageToHull() + result.getEmpDamage();
            if (dmg > 0f) {
                recentDamageTaken += dmg;
                damageWindowTimer = 0.5f;
            }
        }

        @Override
        public void advance(float amount) {
            if (!ship.isAlive() || ship.isHulk() || Global.getCombatEngine().isPaused()) {
                return;
            }

            if (ship.getPhaseCloak() != null && ship.getPhaseCloak().isActive()) {
                ship.getPhaseCloak().deactivate();
            }

            if (damageWindowTimer > 0f) {
                damageWindowTimer -= amount;
                if (damageWindowTimer <= 0f) {
                    recentDamageTaken = 0f;
                }
            }

            // 1. Regenerate charges (0.05/sec = 20s per charge, up to 2 charges)
            if (charges < MAX_CHARGES) {
                regenProgress += CHARGE_REGEN * amount;
                if (regenProgress >= 1f) {
                    charges++;
                    regenProgress = (charges < MAX_CHARGES) ? (regenProgress - 1f) : 0f;
                }
            }

            // 2. Check Right-Click (Manual Player) or Close-Range Threat AI Trigger (Autopilot + AI Ships)
            boolean triggerRequested = false;
            boolean isPlayerShip = (ship == Global.getCombatEngine().getPlayerShip());
            boolean isManualPlayer = isPlayerShip && Global.getCombatEngine().isUIAutopilotOn();

            if (isManualPlayer) {
                boolean rightClickDown = Mouse.isButtonDown(1);
                if (rightClickDown && !rightClickWasPressed) {
                    triggerRequested = true;
                }
                rightClickWasPressed = rightClickDown;
            } else {
                rightClickWasPressed = false;
            }

            // Run AI trigger for non-player ships AND when the player flagship is on Autopilot (U)
            if (!isManualPlayer && !triggerRequested && state == State.IDLE && charges > 0) {
                // A. Trigger under sustained heavy hits (>= 3.5% max HP in 0.5s)
                float damageThreshold = ship.getMaxHitpoints() * 0.035f;
                boolean heavyHits = recentDamageTaken >= damageThreshold;

                // B. Scan for dangerous incoming projectiles/missiles on a collision course within ~220su
                boolean imminentImpact = false;
                float hullRadius = ship.getCollisionRadius();
                float dangerRadius = hullRadius + 220f;
                float dangerRadiusSq = dangerRadius * dangerRadius;
                Vector2f shipLoc = ship.getLocation();

                for (DamagingProjectileAPI proj : Global.getCombatEngine().getProjectiles()) {
                    if (proj.getOwner() == ship.getOwner() || proj.isFading() || proj.didDamage()) continue;
                    if (proj.getDamageAmount() < 120f) continue;

                    float dx = proj.getLocation().x - shipLoc.x;
                    float dy = proj.getLocation().y - shipLoc.y;
                    float distSq = dx * dx + dy * dy;
                    if (distSq > dangerRadiusSq) continue;

                    float vx = proj.getVelocity().x;
                    float vy = proj.getVelocity().y;
                    float speedSq = vx * vx + vy * vy;
                    if (speedSq <= 1f) continue;

                    float toShipX = -dx;
                    float toShipY = -dy;
                    float dot = vx * toShipX + vy * toShipY;
                    if (dot <= 0f) continue;

                    float projDistSq = (toShipX * toShipX + toShipY * toShipY) - (dot * dot) / speedSq;
                    if (projDistSq <= (hullRadius * 1.2f) * (hullRadius * 1.2f)) {
                        imminentImpact = true;
                        break;
                    }
                }

                // C. Emergency trigger if flamed out while taking damage
                boolean emergencyState = ship.getEngineController().isFlamedOut() && recentDamageTaken > 0f;

                if (heavyHits || imminentImpact || emergencyState) {
                    triggerRequested = true;
                    recentDamageTaken = 0f;
                }
            }

            // 3. Handle Activation Input
            if (triggerRequested && state == State.IDLE && !ship.getFluxTracker().isOverloadedOrVenting()) {
                if (charges > 0) {
                    charges--;
                    state = State.CHARGEUP;
                    stateTimer = CHARGE_UP;
                    Global.getSoundPlayer().playSound("system_damper", 1f, 1f, ship.getLocation(), ship.getVelocity());
                } else if (isManualPlayer) {
                    Global.getSoundPlayer().playSound("gun_out_of_ammo", 1f, 1f, ship.getLocation(), ship.getVelocity());
                }
            }

            // 4. Advance State Machine
            float effectLevel = 0f;
            if (state != State.IDLE) {
                stateTimer -= amount;

                if (ship.getFluxTracker().isOverloaded()) {
                    stopSystem();
                } else if (state == State.CHARGEUP) {
                    effectLevel = 1f - Math.max(0f, stateTimer / CHARGE_UP);
                    if (stateTimer <= 0f) {
                        state = State.ACTIVE;
                        stateTimer = ACTIVE_DURATION;
                        effectLevel = 1f;
                    }
                } else if (state == State.ACTIVE) {
                    effectLevel = 1f;
                    Global.getSoundPlayer().playLoop("system_damper_loop", ship, 1f, 1f, ship.getLocation(), ship.getVelocity());
                    if (stateTimer <= 0f) {
                        state = State.CHARGEDOWN;
                        stateTimer = CHARGE_DOWN;
                        Global.getSoundPlayer().playSound("system_damper_off", 1f, 1f, ship.getLocation(), ship.getVelocity());
                    }
                } else if (state == State.CHARGEDOWN) {
                    effectLevel = Math.max(0f, stateTimer / CHARGE_DOWN);
                    if (stateTimer <= 0f) {
                        stopSystem();
                    }
                }
            }

            // 5. Apply exact DamperFieldStats effects & damper.system visuals
            MutableShipStatsAPI stats = ship.getMutableStats();
            if (state != State.IDLE) {
                float mult = DAMAGE_MULT.getOrDefault(ship.getHullSize(), 0.50f);
                stats.getHullDamageTakenMult().modifyMult(modId, mult);
                stats.getArmorDamageTakenMult().modifyMult(modId, mult);
                stats.getEmpDamageTakenMult().modifyMult(modId, mult);

                ship.setJitter(this, JITTER_COLOR, effectLevel, 2, 0f, 5f);
                ship.setJitterUnder(this, JITTER_UNDER_COLOR, effectLevel, 25, 0f, 7f);

                // Disable weapons while Damper Field is active (matches vanilla damper.system)
                for (WeaponAPI weapon : ship.getAllWeapons()) {
                    if (weapon.isDecorative()) continue;
                    weapon.setForceNoFireOneFrame(true);
                }

                if (isPlayerShip) {
                    int percent = (int) Math.round((1f - mult) * 100f);
                    Global.getCombatEngine().maintainStatusForPlayerShip(
                            modId,
                            "graphics/icons/hullsys/damper_field.png",
                            "Damper Field",
                            percent + "% less damage taken",
                            false
                    );
                }
            } else {
                stats.getHullDamageTakenMult().unmodify(modId);
                stats.getArmorDamageTakenMult().unmodify(modId);
                stats.getEmpDamageTakenMult().unmodify(modId);

                if (isPlayerShip) {
                    String chargeText = (charges < MAX_CHARGES)
                            ? String.format("Charges: %d/%d (%d%%)", charges, MAX_CHARGES, (int)(regenProgress * 100))
                            : String.format("Charges: %d/%d (Ready)", charges, MAX_CHARGES);
                    Global.getCombatEngine().maintainStatusForPlayerShip(
                            modId,
                            "graphics/icons/hullsys/damper_field.png",
                            "Damper Field",
                            chargeText,
                            charges == 0
                    );
                }
            }
        }

        private void stopSystem() {
            state = State.IDLE;
            stateTimer = 0f;
            MutableShipStatsAPI stats = ship.getMutableStats();
            stats.getHullDamageTakenMult().unmodify(modId);
            stats.getArmorDamageTakenMult().unmodify(modId);
            stats.getEmpDamageTakenMult().unmodify(modId);
        }
    }
}