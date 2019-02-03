/*
 * Copyright 2017 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License"). See License in the project root for license information.
 *
 */

package com.linkedin.kafka.cruisecontrol.analyzer.goals;

import com.linkedin.kafka.cruisecontrol.analyzer.OptimizationOptions;
import com.linkedin.kafka.cruisecontrol.analyzer.ActionAcceptance;
import com.linkedin.kafka.cruisecontrol.analyzer.AnalyzerUtils;
import com.linkedin.kafka.cruisecontrol.analyzer.BalancingConstraint;
import com.linkedin.kafka.cruisecontrol.analyzer.BalancingAction;
import com.linkedin.kafka.cruisecontrol.analyzer.ActionType;
import com.linkedin.kafka.cruisecontrol.analyzer.goals.internals.CandidateBroker;
import com.linkedin.kafka.cruisecontrol.config.KafkaCruiseControlConfig;
import com.linkedin.kafka.cruisecontrol.exception.OptimizationFailureException;
import com.linkedin.kafka.cruisecontrol.model.Broker;
import com.linkedin.kafka.cruisecontrol.model.ClusterModel;
import com.linkedin.kafka.cruisecontrol.model.ClusterModelStats;
import com.linkedin.kafka.cruisecontrol.model.Replica;

import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Set;

import static com.linkedin.kafka.cruisecontrol.analyzer.ActionAcceptance.ACCEPT;
import static com.linkedin.kafka.cruisecontrol.analyzer.ActionAcceptance.BROKER_REJECT;
import static com.linkedin.kafka.cruisecontrol.analyzer.goals.GoalUtils.legitMove;
import static com.linkedin.kafka.cruisecontrol.analyzer.goals.GoalUtils.eligibleBrokers;
import static com.linkedin.kafka.cruisecontrol.analyzer.goals.GoalUtils.eligibleReplicasForSwap;


/**
 * An abstract class for goals. This class will be extended to crete custom goals for different purposes -- e.g.
 * balancing the distribution of replicas or resources in the cluster.
 */
public abstract class AbstractGoal implements Goal {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractGoal.class);
  private boolean _finished;
  protected boolean _succeeded = true;
  protected BalancingConstraint _balancingConstraint;
  protected int _numWindows;
  protected double _minMonitoredPartitionPercentage;

  /**
   * Constructor of Abstract Goal class sets the _finished flag to false to signal that the goal requirements have not
   * been satisfied, yet.
   */
  public AbstractGoal() {
    _finished = false;
  }

  @Override
  public void configure(Map<String, ?> configs) {
    KafkaCruiseControlConfig parsedConfig = new KafkaCruiseControlConfig(configs, false);
    _balancingConstraint = new BalancingConstraint(parsedConfig);
    _numWindows = parsedConfig.getInt(KafkaCruiseControlConfig.NUM_PARTITION_METRICS_WINDOWS_CONFIG);
    _minMonitoredPartitionPercentage = parsedConfig.getDouble(KafkaCruiseControlConfig.MIN_VALID_PARTITION_RATIO_CONFIG);
  }

  /**
   * @deprecated
   * Please use {@link #optimize(ClusterModel, Set, OptimizationOptions)} instead.
   */
  @Override
  public boolean optimize(ClusterModel clusterModel, Set<Goal> optimizedGoals, Set<String> excludedTopics)
      throws OptimizationFailureException {
    return optimize(clusterModel, optimizedGoals, new OptimizationOptions(excludedTopics));
  }

  @Override
  public boolean optimize(ClusterModel clusterModel, Set<Goal> optimizedGoals, OptimizationOptions optimizationOptions)
      throws OptimizationFailureException {
    Set<String> excludedTopics = optimizationOptions.excludedTopics();
    _succeeded = true;
    LOG.debug("Starting optimization for {}.", name());
    // Initialize pre-optimized stats.
    ClusterModelStats statsBeforeOptimization = clusterModel.getClusterStats(_balancingConstraint);
    LOG.trace("[PRE - {}] {}", name(), statsBeforeOptimization);
    _finished = false;
    long goalStartTime = System.currentTimeMillis();
    initGoalState(clusterModel, excludedTopics);
    Collection<Broker> deadBrokers = clusterModel.deadBrokers();

    while (!_finished) {
      for (Broker broker : brokersToBalance(clusterModel)) {
        rebalanceForBroker(broker, clusterModel, optimizedGoals, optimizationOptions);
      }
      updateGoalState(clusterModel, excludedTopics);
    }
    ClusterModelStats statsAfterOptimization = clusterModel.getClusterStats(_balancingConstraint);
    LOG.trace("[POST - {}] {}", name(), statsAfterOptimization);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Finished optimization for {} in {}ms.", name(), System.currentTimeMillis() - goalStartTime);
    }
    LOG.trace("Cluster after optimization is {}", clusterModel);
    // We only ensure the optimization did not make stats worse when it is not self-healing.
    if (deadBrokers.isEmpty()) {
      ClusterModelStatsComparator comparator = clusterModelStatsComparator();
      // Throw exception when the stats before optimization is preferred.
      if (comparator.compare(statsAfterOptimization, statsBeforeOptimization) < 0) {
        throw new OptimizationFailureException("Optimization for Goal " + name() + " failed because the optimized"
                                               + "result is worse than before. Detail reason: "
                                               + comparator.explainLastComparison());
      }
    }
    return _succeeded;
  }

  @Override
  public abstract String name();

  /**
   * Check whether the replica should be excluded from the rebalance. A replica should be excluded if (1) its topic
   * is in the excluded topics set and (2) its broker is still alive.
   * @param replica the replica to check.
   * @param excludedTopics the excluded topics set.
   * @return true if the replica should be excluded, false otherwise.
   */
  protected static boolean shouldExclude(Replica replica, Set<String> excludedTopics) {
    return excludedTopics.contains(replica.topicPartition().topic()) && replica.originalBroker().isAlive();
  }

  /**
   * Get sorted brokers that the rebalance process will go over to apply balancing actions to replicas they contain.
   *
   * @param clusterModel The state of the cluster.
   * @return A collection of brokers that the rebalance process will go over to apply balancing actions to replicas
   * they contain.
   */
  protected abstract SortedSet<Broker> brokersToBalance(ClusterModel clusterModel);

  /**
   * Check if requirements of this goal are not violated if this action is applied to the given cluster state,
   * false otherwise.
   *
   * @param clusterModel The state of the cluster.
   * @param action Action containing information about potential modification to the given cluster model.
   * @return True if requirements of this goal are not violated if this action is applied to the given cluster state,
   * false otherwise.
   */
  protected abstract boolean selfSatisfied(ClusterModel clusterModel, BalancingAction action);

  /**
   * Signal for finishing the process for rebalance or self-healing for this goal.
   */
  protected void finish() {
    _finished = true;
  }

  /**
   * (1) Initialize states that this goal requires -- e.g. in TopicReplicaDistributionGoal and ReplicaDistributionGoal,
   * this method is used to populate the ReplicaDistributionTarget(s). (2) Run sanity checks regarding minimum
   * requirements of hard goals.
   *
   * @param clusterModel The state of the cluster.
   * @param excludedTopics The topics that should be excluded from the optimization proposals.
   */
  protected abstract void initGoalState(ClusterModel clusterModel, Set<String> excludedTopics)
      throws OptimizationFailureException;

  /**
   * Update goal state after one round of self-healing / rebalance.
   *
   * @param clusterModel The state of the cluster.
   * @param excludedTopics The topics that should be excluded from the optimization action.
   */
  protected abstract void updateGoalState(ClusterModel clusterModel, Set<String> excludedTopics)
      throws OptimizationFailureException;

  /**
   * Rebalance the given broker without violating the constraints of the current goal and optimized goals.
   *
   * @param broker         Broker to be balanced.
   * @param clusterModel   The state of the cluster.
   * @param optimizedGoals Optimized goals.
   * @param optimizationOptions Options to take into account during optimization -- e.g. excluded topics.
   */
  protected abstract void rebalanceForBroker(Broker broker,
                                             ClusterModel clusterModel,
                                             Set<Goal> optimizedGoals,
                                             OptimizationOptions optimizationOptions)
      throws OptimizationFailureException;

  /**
   * Attempt to apply the given balancing action to the given replica in the given cluster. The application
   * considers the candidate brokers as the potential destination brokers for replica movement or the location of
   * followers for leadership transfer. If the movement attempt succeeds, the function returns the broker id of the
   * destination, otherwise the function returns null.
   *
   * @param clusterModel    The state of the cluster.
   * @param replica         Replica to be applied the given balancing action.
   * @param candidateBrokers Candidate brokers as the potential destination brokers for replica movement or the location
   *                        of followers for leadership transfer.
   * @param action          Balancing action.
   * @param optimizedGoals  Optimized goals.
   * @param optimizationOptions Options to take into account during optimization -- e.g. excluded brokers for leadership.
   * @return Broker id of the destination if the movement attempt succeeds, null otherwise.
   */
  protected Broker maybeApplyBalancingAction(ClusterModel clusterModel,
                                             Replica replica,
                                             Collection<Broker> candidateBrokers,
                                             ActionType action,
                                             Set<Goal> optimizedGoals,
                                             OptimizationOptions optimizationOptions) {
    // In self healing mode, allow a move only from dead to alive brokers.
    if (!clusterModel.deadBrokers().isEmpty() && replica.originalBroker().isAlive()) {
      //return null;
      LOG.trace("Applying {} to a replica in a healthy broker in self-healing mode.", action);
    }
    List<Broker> eligibleBrokers = eligibleBrokers(clusterModel, replica, candidateBrokers, action, optimizationOptions);
    for (Broker broker : eligibleBrokers) {
      BalancingAction proposal = new BalancingAction(replica.topicPartition(), replica.broker().id(), broker.id(), action);
      // A replica should be moved if:
      // 0. The move is legit.
      // 1. The goal requirements are not violated if this action is applied to the given cluster state.
      // 2. The movement is acceptable by the previously optimized goals.

      if (!legitMove(replica, broker, action)) {
        LOG.trace("Replica move is not legit for {}.", proposal);
        continue;
      }

      if (!selfSatisfied(clusterModel, proposal)) {
        LOG.trace("Unable to self-satisfy proposal {}.", proposal);
        continue;
      }

      ActionAcceptance acceptance = AnalyzerUtils.isProposalAcceptableForOptimizedGoals(optimizedGoals, proposal, clusterModel);
      LOG.trace("Trying to apply legit and self-satisfied action {}, actionAcceptance = {}", proposal, acceptance);
      if (acceptance == ACCEPT) {
        if (action == ActionType.LEADERSHIP_MOVEMENT) {
          clusterModel.relocateLeadership(replica.topicPartition(), replica.broker().id(), broker.id());
        } else if (action == ActionType.REPLICA_MOVEMENT) {
          clusterModel.relocateReplica(replica.topicPartition(), replica.broker().id(), broker.id());
        }
        return broker;
      }
    }
    return null;
  }

  /**
   * Attempt to swap the given source replica with a replica from the candidate replicas to swap with. The function
   * returns the swapped in replica if succeeded, null otherwise.
   * All the replicas in the given candidateReplicasToSwapWith must be from the same broker.
   *
   * @param clusterModel The state of the cluster.
   * @param sourceReplica Replica to be swapped with.
   * @param cb Candidate broker containing candidate replicas to swap with the source replica in the order of attempts to swap.
   * @param optimizedGoals Optimized goals.
   * @return True the swapped in replica if succeeded, null otherwise.
   */
  Replica maybeApplySwapAction(ClusterModel clusterModel,
                               Replica sourceReplica,
                               CandidateBroker cb,
                               Set<Goal> optimizedGoals) {
    SortedSet<Replica> eligibleReplicas = eligibleReplicasForSwap(clusterModel, sourceReplica, cb);
    if (eligibleReplicas.isEmpty()) {
      return null;
    }

    Broker destinationBroker = eligibleReplicas.first().broker();

    for (Replica destinationReplica : eligibleReplicas) {
      BalancingAction swapProposal = new BalancingAction(sourceReplica.topicPartition(),
                                                         sourceReplica.broker().id(), destinationBroker.id(),
                                                         ActionType.REPLICA_SWAP, destinationReplica.topicPartition());
      // A sourceReplica should be swapped with a replicaToSwapWith if:
      // 0. The swap from source to destination is legit.
      // 1. The swap from destination to source is legit.
      // 2. The goal requirements are not violated if this action is applied to the given cluster state.
      // 3. The movement is acceptable by the previously optimized goals.
      if (!legitMove(sourceReplica, destinationBroker, ActionType.REPLICA_MOVEMENT)) {
        LOG.trace("Swap from source to destination is not legit for {}.", swapProposal);
        return null;
      }

      if (!legitMove(destinationReplica, sourceReplica.broker(), ActionType.REPLICA_MOVEMENT)) {
        LOG.trace("Swap from destination to source is not legit for {}.", swapProposal);
        continue;
      }

      // The current goal is expected to know whether a swap is doable between given brokers.
      if (!selfSatisfied(clusterModel, swapProposal)) {
        // Unable to satisfy proposal for this eligible replica and the remaining eligible replicas in the list.
        LOG.trace("Unable to self-satisfy swap proposal {}.", swapProposal);
        return null;
      }
      ActionAcceptance acceptance = AnalyzerUtils.isProposalAcceptableForOptimizedGoals(optimizedGoals, swapProposal, clusterModel);
      LOG.trace("Trying to apply legit and self-satisfied swap {}, actionAcceptance = {}.", swapProposal, acceptance);

      if (acceptance == ACCEPT) {
        Broker sourceBroker = sourceReplica.broker();
        clusterModel.relocateReplica(sourceReplica.topicPartition(), sourceBroker.id(), destinationBroker.id());
        clusterModel.relocateReplica(destinationReplica.topicPartition(), destinationBroker.id(), sourceBroker.id());
        return destinationReplica;
      } else if (acceptance == BROKER_REJECT) {
        // Unable to swap the given source replica with any replicas in the destination broker.
        return null;
      }
    }
    return null;
  }

  @Override
  public String toString() {
    return name();
  }
}
