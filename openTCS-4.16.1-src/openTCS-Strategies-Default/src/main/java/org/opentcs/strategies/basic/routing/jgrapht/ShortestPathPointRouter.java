/**
 * Copyright (c) The openTCS Authors.
 * <p>
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.strategies.basic.routing.jgrapht;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.opentcs.data.TCSObjectReference;
import org.opentcs.data.model.Point;
import org.opentcs.data.model.Vehicle;
import org.opentcs.data.order.Route;
import org.opentcs.strategies.basic.routing.PointRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static java.util.Objects.requireNonNull;

/**
 * Computes routes between points using a JGraphT-based shortest path algorithm.
 * <p>
 * <em>Note that this implementation does not integrate static routes.</em>
 * </p>
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public class ShortestPathPointRouter
        implements PointRouter {

    /**
     * This class's logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ShortestPathPointRouter.class);

    private final ShortestPathAlgorithm<String, ModelEdge> algo;

    private final Map<String, Point> points = new HashMap<>();

    public ShortestPathPointRouter(ShortestPathAlgorithm<String, ModelEdge> algo,
                                   Collection<Point> points) {
        this.algo = requireNonNull(algo, "algo");
        requireNonNull(points, "points");

        for (Point point : points) {
            this.points.put(point.getName(), point);
        }

    }

    @Override
    public List<Route.Step> getRouteSteps(Point srcPoint, Point destPoint) {
        requireNonNull(srcPoint, "srcPoint");
        requireNonNull(destPoint, "destPoint");
        LOG.debug("【ShortestPathPointRouter.getRouteSteps】开始执行计算「 起点{} --> 终点{} 」 之间的的最短路径",srcPoint.getName(),destPoint.getName());
        long timeBefore = System.currentTimeMillis();
        if (Objects.equals(srcPoint.getName(), destPoint.getName())) {
            return new ArrayList<>();
        }

        GraphPath<String, ModelEdge> graphPath = algo.getPath(srcPoint.getName(), destPoint.getName());
        LOG.debug("【ShortestPathPointRouter.getRouteSteps】获取到Graph图模型中的最短路径graphPath为：【{}】",graphPath);
        if (graphPath == null) {
            return null;
        }
        LOG.debug("【ShortestPathPointRouter.getRouteSteps】开始执行 graphPath --> List<Step> 翻译");

        List<Route.Step> result = translateToSteps(graphPath);
        LOG.debug("【ShortestPathPointRouter.getRouteSteps】graphPath --> List<Step> 翻译完成，输出RouteSteps结果。");

        LOG.debug("Looking up route from {} to {} took {} milliseconds.",
                srcPoint.getName(),
                destPoint.getName(),
                System.currentTimeMillis() - timeBefore);

        return result;
    }

    @Override
    public long getCosts(TCSObjectReference<Point> srcPointRef,
                         TCSObjectReference<Point> destPointRef) {
        requireNonNull(srcPointRef, "srcPointRef");
        requireNonNull(destPointRef, "destPointRef");

        if (Objects.equals(srcPointRef.getName(), destPointRef.getName())) {
            return 0;
        }

        GraphPath<String, ModelEdge> graphPath = algo.getPath(srcPointRef.getName(),
                destPointRef.getName());
        if (graphPath == null) {
            return INFINITE_COSTS;
        }

        return (long) graphPath.getWeight();
    }

    private List<Route.Step> translateToSteps(GraphPath<String, ModelEdge> graphPath) {
        List<ModelEdge> edges = graphPath.getEdgeList();
        List<Route.Step> result = new ArrayList<>(edges.size());

        int routeIndex = 0;
        for (ModelEdge edge : edges) {
            Point sourcePoint = points.get(graphPath.getGraph().getEdgeSource(edge));
            Point destPoint = points.get(graphPath.getGraph().getEdgeTarget(edge));

            result.add(new Route.Step(edge.getModelPath(),
                    sourcePoint,
                    destPoint,
                    orientation(edge, sourcePoint),
                    routeIndex));
            routeIndex++;
        }

        return result;
    }

    /**
     *小车方向判断默认逻辑:
     *  GraphEdge中的起始点位与Model.Point点位是否相同，相同则正向，不同则反响
     *  TODO. 对于四向车，需要判断左转右转的情况下，如何处理？还是说放在CommAdapter里面根据当前起点与所走路径来判断小车是否需要执行转向操作？
     * @author Rico
     * @date 2019/12/11 2:39 下午
     */
    private Vehicle.Orientation orientation(ModelEdge edge, Point graphSourcePoint) {
        return Objects.equals(edge.getModelPath().getSourcePoint(), graphSourcePoint.getReference())
                ? Vehicle.Orientation.FORWARD
                : Vehicle.Orientation.BACKWARD;
    }
}
