/**
 * Copyright (c) The openTCS Authors.
 * <p>
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package org.opentcs.strategies.basic.dispatching.selection.vehicles;

import org.opentcs.data.model.Vehicle;
import org.opentcs.strategies.basic.dispatching.selection.VehicleSelectionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * A collection of {@link VehicleSelectionFilter}s.
 *
 * @author Martin Grzenia (Fraunhofer IML)
 */
public class CompositeVehicleSelectionFilter
        implements VehicleSelectionFilter {
    private static final Logger LOG = LoggerFactory.getLogger(CompositeVehicleSelectionFilter.class);


    /**
     * The {@link VehicleSelectionFilter}s.
     */
    private final Set<VehicleSelectionFilter> filters;

    @Inject
    public CompositeVehicleSelectionFilter(Set<VehicleSelectionFilter> filters) {
        LOG.debug("【CompositeVehicleSelectionFilter】初始化组合小车选择过滤器，注入filters为[{}]", filters);
        this.filters = requireNonNull(filters, "filters");
    }

    @Override
    public Collection<String> apply(Vehicle vehicle) {
        LOG.debug("【CompositeVehicleSelectionFilter组合过滤器】对{}执行过滤条件{}", vehicle.getName(), this.filters);
        return filters.stream()
                .flatMap(filter -> filter.apply(vehicle).stream())
                .collect(Collectors.toList());
    }
}
