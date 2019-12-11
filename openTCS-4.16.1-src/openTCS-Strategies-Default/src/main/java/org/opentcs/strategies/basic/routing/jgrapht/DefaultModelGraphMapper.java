/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.strategies.basic.routing.jgrapht;

import java.util.Collection;
import static java.util.Objects.requireNonNull;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import org.jgrapht.Graph;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.opentcs.data.model.Path;
import org.opentcs.data.model.Point;
import org.opentcs.data.model.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mapper to translate a collection of points and paths into a weighted graph.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public class DefaultModelGraphMapper
    implements ModelGraphMapper {

  /**
   * This class's logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(DefaultModelGraphMapper.class);
  /**
   * Computes the weight of single edges in the graph.
   */
  private final EdgeEvaluator edgeEvaluator;
  /**
   * The configuration.
   */
  private final ShortestPathConfiguration configuration;

  /**
   * Creates a new instance.
   *
   * @param edgeEvaluator Computes the weight of single edges in the graph.
   * @param configuration The configuration.
   */
  @Inject
  public DefaultModelGraphMapper(@Nonnull EdgeEvaluator edgeEvaluator,
                                 @Nonnull ShortestPathConfiguration configuration) {
    this.edgeEvaluator = requireNonNull(edgeEvaluator, "edgeEvaluator");
    this.configuration = requireNonNull(configuration, "configuration");
  }

  /**
   *构建计算模型图过程：
   * 1. Model.Points =={graph.addVertex(point.getName());}==> Graph.Vertex
   * 2.Model.Path -> Graph.Edge
   *  2.1.ForwardEdge:
   *    2.1.1.Model.Path -> new Edge
   *    2.1.2.weight = edgeEvaluator.computeWeight(edge, vehicle)
   *    2.1.3.Graph.addEdge(sourceVertex,targetVertex, edge);
   *    2.1.4.graph.setEdgeWeight(edge, weight);
   *  2.2.ReverseEdge
   * @author Rico
   * @date 2019/12/11 2:12 下午
   */
  @Override
  public Graph<String, ModelEdge> translateModel(Collection<Point> points,
                                                 Collection<Path> paths,
                                                 Vehicle vehicle) {
    requireNonNull(points, "points");
    requireNonNull(paths, "paths");
    requireNonNull(vehicle, "vehicle");
    LOG.debug("【DefaultModelGraphMapper.translateModel】即将为{}小车构建图模型。");
    LOG.debug("  /** ");
    LOG.debug("   *构建计算模型图过程： ");
    LOG.debug("   * 1. Model.Points =={graph.addVertex(point.getName());}==> Graph.Vertex ");
    LOG.debug("   * 2.Model.Path -> Graph.Edge ");
    LOG.debug("   *  2.1.ForwardEdge: ");
    LOG.debug("   *    2.1.1.Model.Path -> new Edge ");
    LOG.debug("   *    2.1.2.weight = edgeEvaluator.computeWeight(edge, vehicle) ");
    LOG.debug("   *    2.1.3.Graph.addEdge(sourceVertex,targetVertex, edge); ");
    LOG.debug("   *    2.1.4.graph.setEdgeWeight(edge, weight); ");
    LOG.debug("   *  2.2.ReverseEdge ");
    LOG.debug("   * @author Rico ");
    LOG.debug("   * @date 2019/12/11 2:12 下午 ");
    LOG.debug("   */ ");




    Graph<String, ModelEdge> graph = new DirectedWeightedMultigraph<>(ModelEdge.class);

    for (Point point : points) {
      graph.addVertex(point.getName());
    }

    boolean allowNegativeEdgeWeights = configuration.algorithm().isHandlingNegativeCosts();

    for (Path path : paths) {

      if (shouldAddForwardEdge(path, vehicle)) {
        ModelEdge edge = new ModelEdge(path, false);
        double weight = edgeEvaluator.computeWeight(edge, vehicle);

        if (weight < 0 && !allowNegativeEdgeWeights) {
          LOG.warn("Edge {} with weight {} ignored. Algorithm {} cannot handle negative weights.",
                   edge,
                   weight,
                   configuration.algorithm().name());
        }
        else {
          graph.addEdge(path.getSourcePoint().getName(),
                        path.getDestinationPoint().getName(),
                        edge);
          graph.setEdgeWeight(edge, weight);
        }
      }

      if (shouldAddReverseEdge(path, vehicle)) {
        ModelEdge edge = new ModelEdge(path, true);
        double weight = edgeEvaluator.computeWeight(edge, vehicle);

        if (weight < 0 && !allowNegativeEdgeWeights) {
          LOG.warn("Edge {} with weight {} ignored. Algorithm {} cannot handle negative weights.",
                   edge,
                   weight,
                   configuration.algorithm().name());
        }
        else {
          graph.addEdge(path.getDestinationPoint().getName(),
                        path.getSourcePoint().getName(),
                        edge);
          graph.setEdgeWeight(edge, weight);
        }
      }

    }
      LOG.debug("【DefaultModelGraphMapper.translateModel】已为{}小车构建图模型{}。",vehicle.getName(),graph.getClass());
    return graph;
  }

  /**
   * Returns <code>true</code> if and only if the graph should contain an edge from the source
   * of the path to its destination for the given vehicle.
   *
   * @param path The path
   * @param vehicle The vehicle
   * @return <code>true</code> if and only if the graph should contain the edge
   */
  protected boolean shouldAddForwardEdge(Path path, Vehicle vehicle) {
    return path.isNavigableForward();
  }

  /**
   * Returns <code>true</code> if and only if the graph should contain an edge from the destination
   * of the path to its source for the given vehicle.
   *
   * @param path The path
   * @param vehicle The vehicle
   * @return <code>true</code> if and only if the graph should contain the edge
   */
  protected boolean shouldAddReverseEdge(Path path, Vehicle vehicle) {
    return path.isNavigableReverse();
  }
}
