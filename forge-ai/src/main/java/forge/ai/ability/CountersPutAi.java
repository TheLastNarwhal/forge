package forge.ai.ability;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import forge.ai.*;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityUtils;
import forge.game.card.*;
import forge.game.combat.CombatUtil;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostRemoveCounter;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerPredicates;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;
import forge.util.MyRandom;

public class CountersPutAi extends SpellAbilityAi {

    /*
     * (non-Javadoc)
     * 
     * @see forge.ai.SpellAbilityAi#willPayCosts(forge.game.player.Player,
     * forge.game.spellability.SpellAbility, forge.game.cost.Cost,
     * forge.game.card.Card)
     */
    @Override
    protected boolean willPayCosts(Player ai, SpellAbility sa, Cost cost, Card source) {

        final String type = sa.getParam("CounterType");
        // TODO Auto-generated method stub
        if (!super.willPayCosts(ai, sa, cost, source)) {
            return false;
        }

        // disable moving counters
        for (final CostPart part : cost.getCostParts()) {
            if (part instanceof CostRemoveCounter) {
                final CostRemoveCounter remCounter = (CostRemoveCounter) part;
                final CounterType counterType = remCounter.counter;
                if (counterType.name().equals(type)) {
                    return false;
                }
                if (!part.payCostFromSource()) {
                    if (counterType.equals(CounterType.P1P1)) {
                        return false;
                    }
                    continue;
                }
                // don't kill the creature
                if (counterType.equals(CounterType.P1P1) && source.getLethalDamage() <= 1) {
                    return false;
                }
            }
        }

        return true;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * forge.ai.SpellAbilityAi#checkPhaseRestrictions(forge.game.player.Player,
     * forge.game.spellability.SpellAbility, forge.game.phase.PhaseHandler)
     */
    @Override
    protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, PhaseHandler ph) {
        final Card source = sa.getHostCard();

        if (sa.isOutlast()) {
            if (ph.is(PhaseType.MAIN2, ai)) { // applicable to non-attackers
                                              // only
                float chance = 0.8f;
                if (ComputerUtilCard.doesSpecifiedCreatureBlock(ai, source)) {
                    return false;
                }
                return chance > MyRandom.getRandom().nextFloat();
            } else {
                return false;
            }
        }

        if (sa.hasParam("LevelUp")) {
            // creatures enchanted by curse auras have low priority
            if (ph.getPhase().isBefore(PhaseType.MAIN2)) {
                for (Card aura : source.getEnchantedBy(false)) {
                    if (aura.getController().isOpponentOf(ai)) {
                        return false;
                    }
                }
            }
            int maxLevel = Integer.parseInt(sa.getParam("MaxLevel"));
            return source.getCounters(CounterType.LEVEL) < maxLevel;
        }

        return super.checkPhaseRestrictions(ai, sa, ph);
    }

    @Override
    protected boolean checkApiLogic(Player ai, final SpellAbility sa) {
        // AI needs to be expanded, since this function can be pretty complex
        // based on
        // what the expected targets could be
        final Random r = MyRandom.getRandom();
        final Cost abCost = sa.getPayCosts();
        final Card source = sa.getHostCard();
        CardCollection list;
        Card choice = null;
        final String type = sa.getParam("CounterType");
        final String amountStr = sa.getParam("CounterNum");
        final boolean divided = sa.hasParam("DividedAsYouChoose");

        if ("ExistingCounter".equals(type)) {
            final boolean eachExisting = sa.hasParam("EachExistingCounter");
            // Prevent animation module from crashing for now
            // TODO needs AI for targeting with this type

            if (sa.usesTargeting()) {
                sa.resetTargets();
                final TargetRestrictions abTgt = sa.getTargetRestrictions();
                if (abTgt.canTgtPlayer()) {
                    // try to kill opponent with Poison
                    PlayerCollection oppList = ai.getOpponents().filter(PlayerPredicates.isTargetableBy(sa));
                    PlayerCollection poisonList = oppList.filter(PlayerPredicates.hasCounter(CounterType.POISON, 9));
                    if (!poisonList.isEmpty()) {
                        sa.getTargets().add(poisonList.max(PlayerPredicates.compareByLife()));
                        return true;
                    }
                }

                if (abTgt.canTgtCreature()) {
                    // try to kill creature with -1/-1 counters if it can
                    // receive counters, execpt it has undying
                    CardCollection oppCreat = CardLists.getTargetableCards(ai.getOpponents().getCreaturesInPlay(), sa);
                    CardCollection oppCreatM1 = CardLists.filter(oppCreat, CardPredicates.hasCounter(CounterType.M1M1));
                    oppCreatM1 = CardLists.getNotKeyword(oppCreatM1, "Undying");

                    oppCreatM1 = CardLists.filter(oppCreatM1, new Predicate<Card>() {
                        @Override
                        public boolean apply(Card input) {
                            return input.getNetToughness() <= 1 && input.canReceiveCounters(CounterType.M1M1);
                        }

                    });

                    Card best = ComputerUtilCard.getBestAI(oppCreatM1);
                    if (best != null) {
                        sa.getTargets().add(best);
                        return true;
                    }

                    CardCollection aiCreat = CardLists.getTargetableCards(ai.getCreaturesInPlay(), sa);
                    aiCreat = CardLists.filter(aiCreat, CardPredicates.hasCounters());

                    aiCreat = CardLists.filter(aiCreat, new Predicate<Card>() {
                        @Override
                        public boolean apply(Card input) {
                            for (CounterType counterType : input.getCounters().keySet()) {
                                if (!ComputerUtil.isNegativeCounter(counterType, input)
                                        && input.canReceiveCounters(counterType)) {
                                    return true;
                                }
                            }
                            return false;
                        }
                    });

                    // TODO check whole state which creature would be the best
                    best = ComputerUtilCard.getBestAI(aiCreat);
                    if (best != null) {
                        sa.getTargets().add(best);
                        return true;
                    }
                }

                if (sa.canTarget(ai)) {
                    // don't target itself when its forced to add poison
                    // counters too
                    if (!eachExisting || ai.getPoisonCounters() < 5) {
                        sa.getTargets().add(ai);
                        return true;
                    }
                }

            }

            return false;
        }

        if (ComputerUtil.preventRunAwayActivations(sa)) {
            return false;
        }

        if ("Never".equals(sa.getParam("AILogic"))) {
            return false;
        }
        
        if ("PayEnergy".equals(sa.getParam("AILogic"))) {
            return true;
        }

        if (sa.getConditions() != null && !sa.getConditions().areMet(sa) && sa.getSubAbility() == null) {
            return false;
        }
        
        if (source.getName().equals("Feat of Resistance")) {    // sub-ability should take precedence
            CardCollection prot = ProtectAi.getProtectCreatures(ai, sa.getSubAbility());
            if (!prot.isEmpty()) {
                sa.getTargets().add(prot.get(0));
                return true;
            }
        }

        if (sa.hasParam("Bolster")) {
            CardCollection creatsYouCtrl = ai.getCreaturesInPlay();
            CardCollection leastToughness = new CardCollection(Aggregates.listWithMin(creatsYouCtrl, CardPredicates.Accessors.fnGetDefense));
            if (leastToughness.isEmpty()) {
                return false;
            }
            // TODO If Creature that would be Bolstered for some reason is useless, also return False
        }

        if (sa.hasParam("Monstrosity") && source.isMonstrous()) {
            return false;
        }

        // TODO handle proper calculation of X values based on Cost
        int amount = AbilityUtils.calculateAmount(source, amountStr, sa);

        if ("Fight".equals(sa.getParam("AILogic"))) {
            int nPump = 0;
            if (type.equals("P1P1")) {
                nPump = amount;
            }
            return FightAi.canFightAi(ai, sa, nPump, nPump);
        }
        
        if (amountStr.equals("X") && source.getSVar(amountStr).equals("Count$xPaid")) {
            // Set PayX here to maximum value.
            amount = ComputerUtilMana.determineLeftoverMana(sa, ai);
            source.setSVar("PayX", Integer.toString(amount));
        }

        // don't use it if no counters to add
        if (amount <= 0) {
            return false;
        }

        if ("Polukranos".equals(sa.getParam("AILogic"))) {

            CardCollection humCreatures = CardLists.getTargetableCards(ai.getOpponents().getCreaturesInPlay(), sa);

            final CardCollection targets = CardLists.filter(humCreatures, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    return !(c.hasProtectionFrom(source) || c.hasKeyword("Shroud") || c.hasKeyword("Hexproof"));
                }
            });
            if (!targets.isEmpty()){
                boolean canSurvive = false;
                for (Card humanCreature : targets) {
                    if (!FightAi.canKill(humanCreature, source, 0)){
                        canSurvive = true;
                    }
                }
                if (!canSurvive){
                    return false;
                }
            }
        }
        
        PhaseHandler ph = ai.getGame().getPhaseHandler();
        
        if (!ai.getGame().getStack().isEmpty() && !SpellAbilityAi.isSorcerySpeed(sa)) {
            final TargetRestrictions abTgt = sa.getTargetRestrictions();
            // only evaluates case where all tokens are placed on a single target
            if (sa.usesTargeting() && abTgt.getMinTargets(source, sa) < 2) {
                if (ComputerUtilCard.canPumpAgainstRemoval(ai, sa)) {
                    Card c = sa.getTargets().getFirstTargetedCard();
                    if (sa.getTargets().getNumTargeted() > 1) {
                        sa.resetTargets();
                        sa.getTargets().add(c);
                    }
                    abTgt.addDividedAllocation(sa.getTargetCard(), amount);
                    return true;
                } else {
                    return false;
                }
            }
        }

        // Targeting
        if (sa.usesTargeting()) {
            sa.resetTargets();            
            
            final boolean sacSelf = ComputerUtilCost.isSacrificeSelfCost(abCost);

            if (sa.isCurse()) {
                list = ai.getOpponents().getCardsIn(ZoneType.Battlefield);
            } else {
                list = new CardCollection(ai.getCardsIn(ZoneType.Battlefield));
            }

            list = CardLists.filter(list, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {

                    // don't put the counter on the dead creature
                    if (sacSelf && c.equals(source)) {
                        return false;
                    }
                    return sa.canTarget(c) && c.canReceiveCounters(CounterType.valueOf(type));
                }
            });

            if (list.size() < sa.getTargetRestrictions().getMinTargets(source, sa)) {
                return false;
            }

            if (source.getName().equals("Abzan Charm")) {
                final TargetRestrictions abTgt = sa.getTargetRestrictions();
                // specific AI for instant with distribute two +1/+1 counters
                ComputerUtilCard.sortByEvaluateCreature(list);
                // maximise the number of targets
                for (int i = 1; i < amount + 1; i++) {
                    int left = amount;
                    for (Card c : list) {
                        if (ComputerUtilCard.shouldPumpCard(ai, sa, c, i, i,
                                Lists.<String>newArrayList())) {
                            sa.getTargets().add(c);
                            abTgt.addDividedAllocation(c, i);
                            left -= i;
                        }
                        if (left < i || sa.getTargets().getNumTargeted() == abTgt.getMaxTargets(source, sa)) {
                            abTgt.addDividedAllocation(sa.getTargets().getFirstTargetedCard(), left + i);
                            left = 0;
                            break;
                        }
                    }
                    if (left == 0) {
                        return true;
                    }
                    sa.resetTargets();
                }
                return false;
            }
            
            // target loop
            while (sa.canAddMoreTarget()) {
                if (list.isEmpty()) {
                    if (!sa.isTargetNumberValid() || (sa.getTargets().getNumTargeted() == 0)) {
                        sa.resetTargets();
                        return false;
                    } else {
                        // TODO is this good enough? for up to amounts?
                        break;
                    }
                }

                if (sa.isCurse()) {
                    choice = CountersAi.chooseCursedTarget(list, type, amount);
                } else {
                    if (type.equals("P1P1") && !SpellAbilityAi.isSorcerySpeed(sa)) {
                        for (Card c : list) {
                            if (ComputerUtilCard.shouldPumpCard(ai, sa, c, amount, amount,
                                    Lists.<String>newArrayList())) {
                                choice = c;
                                break;
                            }
                        }
                        if (!source.isSpell()) {    // does not cost a card
                            if (choice == null) {   // find generic target
                                if (abCost == null
                                        || (ph.is(PhaseType.END_OF_TURN) && ph.getPlayerTurn().isOpponentOf(ai))) {
                                    // only use at opponent EOT unless it is free
                                    choice = CountersAi.chooseBoonTarget(list, type);
                                }
                            }
                        }
                        if (sa.getHostCard().getName().equals("Dromoka's Command")) {
                            choice = CountersAi.chooseBoonTarget(list, type);
                        }
                    } else {
                        choice = CountersAi.chooseBoonTarget(list, type);
                    }
                }

                if (choice == null) { // can't find anything left
                    if (!sa.isTargetNumberValid() || sa.getTargets().getNumTargeted() == 0) {
                        sa.resetTargets();
                        return false;
                    } else {
                        // TODO is this good enough? for up to amounts?
                        break;
                    }
                }

                list.remove(choice);
                sa.getTargets().add(choice);
                if (divided) {
                    sa.getTargetRestrictions().addDividedAllocation(choice, amount);
                    break;
                }
                choice = null;
            }
            if (sa.getTargets().isEmpty()) {
                return false;
            }
        } else {
            final List<Card> cards = AbilityUtils.getDefinedCards(sa.getHostCard(), sa.getParam("Defined"), sa);
            // Don't activate Curse abilities on my cards and non-curse abilites
            // on my opponents
            if (cards.isEmpty() || !cards.get(0).getController().isOpponentOf(ai)) {
                return false;
            }

            final int currCounters = cards.get(0).getCounters(CounterType.valueOf(type));
            // each non +1/+1 counter on the card is a 10% chance of not
            // activating this ability.

            if (!(type.equals("P1P1") || type.equals("M1M1") || type.equals("ICE")) && (r.nextFloat() < (.1 * currCounters))) {
                return false;
            }
        }

        boolean immediately = ComputerUtil.playImmediately(ai, sa);
        
        if (abCost != null && !ComputerUtilCost.checkSacrificeCost(ai, abCost, source, immediately)) {
            return false;
        }
        
        if (immediately) {
            return true;
        }

        if (!type.equals("P1P1") && !type.equals("M1M1") && !sa.hasParam("ActivationPhases")) {
            // Don't use non P1P1/M1M1 counters before main 2 if possible
            if (ph.getPhase().isBefore(PhaseType.MAIN2) && !ComputerUtil.castSpellInMain1(ai, sa)) {
                return false;
            }
            if (ph.isPlayerTurn(ai) && !isSorcerySpeed(sa)) {
                return false;
            }
        }

        if (ComputerUtil.waitForBlocking(sa)) {
            return false;
        }
        
        return true;
    }

    @Override
    public boolean chkAIDrawback(final SpellAbility sa, Player ai) {
        boolean chance = true;
        // final TargetRestrictions abTgt = sa.getTargetRestrictions();
        final Card source = sa.getHostCard();
        final Game game = source.getGame();
        Card choice = null;
        final String type = sa.getParam("CounterType");

        final String amountStr = sa.getParam("CounterNum");
        final boolean divided = sa.hasParam("DividedAsYouChoose");
        final int amount = AbilityUtils.calculateAmount(sa.getHostCard(), amountStr, sa);

        if (sa.usesTargeting()) {
            CardCollection list = null;

            if (sa.isCurse()) {
                list = CardLists.filterControlledBy(game.getCardsIn(ZoneType.Battlefield), ai.getOpponents());
            } else {
                list = new CardCollection(ai.getCardsIn(ZoneType.Battlefield));
            }

            list = CardLists.getTargetableCards(list, sa);

            if (list.size() == 0) {
                return false;
            }

            sa.resetTargets();
            // target loop
            while (sa.canAddMoreTarget()) {

                if (list.size() == 0) {
                    if (!sa.isTargetNumberValid()
                            || (sa.getTargets().getNumTargeted() == 0)) {
                        sa.resetTargets();
                        return false;
                    } else {
                        break;
                    }
                }

                if (sa.isCurse()) {
                    choice = CountersAi.chooseCursedTarget(list, type, amount);
                } else {
                    String txt = source.getAbilityText();
                    if (txt != null && txt.contains("Awaken ")) {
                        choice = ComputerUtilCard.getWorstLand(list);
                    } else {
                        choice = CountersAi.chooseBoonTarget(list, type);
                    }
                }

                if (choice == null) { // can't find anything left
                    if ((!sa.isTargetNumberValid())
                            || (sa.getTargets().getNumTargeted() == 0)) {
                        sa.resetTargets();
                        return false;
                    } else {
                        // TODO is this good enough? for up to amounts?
                        break;
                    }
                }
                list.remove(choice);
                sa.getTargets().add(choice);
                if (divided) {
                    sa.getTargetRestrictions().addDividedAllocation(choice, amount);
                    break;
                }
            }
        }

        return chance;
    }

    @Override
    protected boolean doTriggerAINoCost(Player ai, SpellAbility sa, boolean mandatory) {
        final Card source = sa.getHostCard();
        // boolean chance = true;
        boolean preferred = true;
        CardCollection list;
        final String type = sa.getParam("CounterType");
        final String amountStr = sa.getParam("CounterNum");
        final boolean divided = sa.hasParam("DividedAsYouChoose");
        final int amount = AbilityUtils.calculateAmount(sa.getHostCard(), amountStr, sa);
        int left = amount;
        
        if (!sa.usesTargeting()) {
            // No target. So must be defined
            list = new CardCollection(AbilityUtils.getDefinedCards(source, sa.getParam("Defined"), sa));
            
            if (amountStr.equals("X") && ((sa.hasParam(amountStr) && sa.getSVar(amountStr).equals("Count$xPaid")) || source.getSVar(amountStr).equals("Count$xPaid") )) {
                // Spend all remaining mana to add X counters (eg. Hero of Leina Tower)
                source.setSVar("PayX", Integer.toString(ComputerUtilMana.determineLeftoverMana(sa, ai)));
            }
            
            if (!mandatory) {
                // TODO - If Trigger isn't mandatory, when wouldn't we want to
                // put a counter?
                // things like Powder Keg, which are way too complex for the AI
            }
        } else {
            if (sa.isCurse()) {
                list = ai.getOpponents().getCardsIn(ZoneType.Battlefield);
            } else {
                list = new CardCollection(ai.getCardsIn(ZoneType.Battlefield));
            }
            list = CardLists.getTargetableCards(list, sa);

            int totalTargets = list.size();

            while (sa.canAddMoreTarget()) {
                if (mandatory) {
                    // When things are mandatory, gotta handle a little differently
                    if ((list.isEmpty() || !preferred) && sa.isTargetNumberValid()) {
                        return true;
                    }

                    if (list.isEmpty() && preferred) {
                        // If it's required to choose targets and the list is empty, get a new list
                        list = CardLists.getTargetableCards(ai.getOpponents().getCardsIn(ZoneType.Battlefield), sa);
                        preferred = false;
                    }
                }

                if (list.isEmpty()) {
                    // Not mandatory, or the the list was regenerated and is still empty,
                    // so return whether or not we found enough targets
                    return sa.isTargetNumberValid();
                }

                Card choice = null;

                // Choose targets here:
                if (sa.isCurse()) {
                    if (preferred) {
                        choice = CountersAi.chooseCursedTarget(list, type, amount);
                        if (choice == null && mandatory) {
                            choice = Aggregates.random(list);
                        }
                    } else {
                        if (type.equals("M1M1")) {
                            choice = ComputerUtilCard.getWorstCreatureAI(list);
                        } else {
                            choice = Aggregates.random(list);
                        }
                    }
                } else {
                    if (preferred) {
                        list = ComputerUtil.getSafeTargets(ai, sa, list);
                        choice = CountersAi.chooseBoonTarget(list, type);
                        if (choice == null && mandatory) {
                            choice = Aggregates.random(list);
                        }
                    } else {
                        if (type.equals("P1P1")) {
                            choice = ComputerUtilCard.getWorstCreatureAI(list);
                        } else {
                            choice = Aggregates.random(list);
                        }
                    }
                    if (choice != null && divided) {
                        final TargetRestrictions abTgt = sa.getTargetRestrictions();
                        int alloc = Math.max(amount / totalTargets, 1);
                        if (sa.getTargets().getNumTargeted() == Math.min(totalTargets, abTgt.getMaxTargets(sa.getHostCard(), sa)) - 1) {
                            abTgt.addDividedAllocation(choice, left);
                        } else {
                            abTgt.addDividedAllocation(choice, alloc);
                            left -= alloc;
                        }
                    }
                }

                if (choice != null) {
                    sa.getTargets().add(choice);
                    list.remove(choice);
                } else {
                    // Didn't want to choose anything?
                    list.clear();
                }

            }
        }
        return true;
    }

    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message) {
        final Card source = sa.getHostCard();
        if (mode == PlayerActionConfirmMode.Tribute) {
            // add counter if that opponent has a giant creature
            final List<Card> creats = player.getCreaturesInPlay();
            final int tributeAmount = AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("CounterNum"), sa);

            final boolean isHaste = source.hasKeyword("Haste");
            List<Card> threatening = CardLists.filter(creats, new Predicate<Card>() {
                @Override
                public boolean apply(Card c) {
                    return CombatUtil.canBlock(source, c, !isHaste) 
                            && (c.getNetToughness() > source.getNetPower() + tributeAmount || c.hasKeyword("DeathTouch"));
                }
            });
            if (!threatening.isEmpty()) {
                return true;
            }
            if (source.hasSVar("TributeAILogic")) {
                final String logic = source.getSVar("TributeAILogic");
                if (logic.equals("Always")) {
                    return true;
                } else if (logic.equals("Never")) {
                    return false;
                } else if (logic.equals("CanBlockThisTurn")) {
                    // pump haste
                    List<Card> canBlock = CardLists.filter(creats, new Predicate<Card>() {
                        @Override
                        public boolean apply(Card c) {
                            return CombatUtil.canBlock(source, c) && (c.getNetToughness() > source.getNetPower() || c.hasKeyword("DeathTouch"));
                        }
                    });
                    if (!canBlock.isEmpty()) {
                        return false;
                    }
                } else if (logic.equals("DontControlCreatures")) {
                    return !creats.isEmpty();
                } else if (logic.equals("OppHasCardsInHand")) {
                    return sa.getActivatingPlayer().getCardsIn(ZoneType.Hand).isEmpty();
                }
            }
        }
        return MyRandom.getRandom().nextBoolean();
    }

    @Override
    public Player chooseSinglePlayer(Player ai, SpellAbility sa, Iterable<Player> options) {
        // used by Tribute, select player with lowest Life
        // TODO add more logic using TributeAILogic
        List<Player> list = Lists.newArrayList(options);
        return Collections.min(list, PlayerPredicates.compareByLife());
    }
    
    @Override
    protected Card chooseSingleCard(final Player ai, SpellAbility sa, Iterable<Card> options, boolean isOptional, Player targetedPlayer) {
        // Bolster does use this
        // TODO need more or less logic there?

        // no logic if there is no options or no to choice
        if (!isOptional && Iterables.size(options) <= 1) {
            return Iterables.getFirst(options, null);
        }

        final CounterType type = CounterType.valueOf(sa.getParam("CounterType"));
        final String amountStr = sa.getParam("CounterNum");
        final int amount = AbilityUtils.calculateAmount(sa.getHostCard(), amountStr, sa);

        final boolean isCurse = sa.isCurse();

        if (isCurse) {
            final CardCollection opponents = CardLists.filterControlledBy(options, ai.getOpponents());

            if (!opponents.isEmpty()) {
                final CardCollection negative = CardLists.filter(opponents, new Predicate<Card>() {
                    @Override
                    public boolean apply(Card input) {
                        if (input.hasSVar("EndOfTurnLeavePlay"))
                            return false;
                        if (ComputerUtilCard.isUselessCreature(ai, input))
                            return false;
                        if (CounterType.M1M1.equals(type) && amount >= input.getNetToughness())
                            return true;
                        return ComputerUtil.isNegativeCounter(type, input);
                    }
                });
                if (!negative.isEmpty()) {
                    return ComputerUtilCard.getBestAI(negative);
                }
            }
        }

        final CardCollection mine = CardLists.filterControlledBy(options, ai);
        // none of mine?
        if (mine.isEmpty()) {
            // Try to Benefit Ally if possible
            final CardCollection ally = CardLists.filterControlledBy(options, ai.getAllies());
            if (ally.isEmpty()) {
                return ComputerUtilCard.getBestAI(ally);
            }
            return isOptional ? null : ComputerUtilCard.getWorstAI(options);
        }

        CardCollection filtered = mine;

        final CardCollection notUseless = CardLists.filter(filtered, new Predicate<Card>() {
            @Override
            public boolean apply(Card input) {
                if (input.hasSVar("EndOfTurnLeavePlay"))
                    return false;
                return !ComputerUtilCard.isUselessCreature(ai, input);
            }
        });

        if (!notUseless.isEmpty()) {
            filtered = notUseless;
        }

        // some special logic to reload Persist/Undying
        if (CounterType.P1P1.equals(type)) {
            final CardCollection persist = CardLists.filter(filtered, new Predicate<Card>() {
                @Override
                public boolean apply(Card input) {
                    if (!input.hasKeyword("Persist"))
                        return false;
                    return input.getCounters(CounterType.M1M1) <= amount;
                }
            });

            if (!persist.isEmpty()) {
                filtered = persist;
            }
        } else if (CounterType.M1M1.equals(type)) {
            final CardCollection undying = CardLists.filter(filtered, new Predicate<Card>() {
                @Override
                public boolean apply(Card input) {
                    if (!input.hasKeyword("Undying"))
                        return false;
                    return input.getCounters(CounterType.P1P1) <= amount && input.getNetToughness() > amount;
                }
            });

            if (!undying.isEmpty()) {
                filtered = undying;
            }
        }

        return ComputerUtilCard.getBestAI(filtered);
    }

    /**
     * used for ExistingCounterType
     */
    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, Map<String, Object> params) {
        Player ai = sa.getActivatingPlayer();
        GameEntity e = (GameEntity) params.get("Target");
        // for Card try to select not useless counter
        if (e instanceof Card) {
            Card c = (Card) e;
            if (c.getController().isOpponentOf(ai)) {
                if (options.contains(CounterType.M1M1) && !c.hasKeyword("Undying")) {
                    return CounterType.M1M1;
                }
                for (CounterType type : options) {
                    if (ComputerUtil.isNegativeCounter(type, c)) {
                        return type;
                    }
                }
            } else {
                for (CounterType type : options) {
                    if (!ComputerUtil.isNegativeCounter(type, c) && !ComputerUtil.isUselessCounter(type)) {
                        return type;
                    }
                }
            }
        } else if (e instanceof Player) {
            Player p = (Player) e;
            if (p.isOpponentOf(ai)) {
                if (options.contains(CounterType.POISON)) {
                    return CounterType.POISON;
                }
            } else {
                if (options.contains(CounterType.EXPERIENCE)) {
                    return CounterType.EXPERIENCE;
                }
            }

        }
        return Iterables.getFirst(options, null);
    }
}
