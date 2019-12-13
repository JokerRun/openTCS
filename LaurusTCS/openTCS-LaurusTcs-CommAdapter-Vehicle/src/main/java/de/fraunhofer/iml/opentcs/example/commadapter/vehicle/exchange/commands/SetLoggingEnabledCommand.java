/**
 * Copyright (c) Fraunhofer IML
 */
package de.fraunhofer.iml.opentcs.example.commadapter.vehicle.exchange.commands;

import de.fraunhofer.iml.opentcs.example.commadapter.vehicle.LarusCommAdapter;
import org.opentcs.drivers.vehicle.AdapterCommand;
import org.opentcs.drivers.vehicle.VehicleCommAdapter;

/**
 * A command to enable/disable logging.
 *
 * @author Martin Grzenia (Fraunhofer IML)
 */
public class SetLoggingEnabledCommand
    implements AdapterCommand {

  /**
   * The new logging state.
   */
  private final boolean enabled;

  /**
   * Creates a new instance.
   *
   * @param enabled The new logging state.
   */
  public SetLoggingEnabledCommand(boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public void execute(VehicleCommAdapter adapter) {
    if (!(adapter instanceof LarusCommAdapter)) {
      return;
    }

    LarusCommAdapter exampleAdapter = (LarusCommAdapter) adapter;
    exampleAdapter.getProcessModel().setLoggingEnabled(enabled);
  }
}
