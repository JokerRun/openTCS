/**
 * Copyright (c) Fraunhofer IML
 */
package de.fraunhofer.iml.opentcs.example.commadapter.vehicle;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import org.opentcs.customizations.kernel.KernelInjectionModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LarusKernelInjectionModule
    extends KernelInjectionModule {
  
  private static final Logger LOG = LoggerFactory.getLogger(LarusKernelInjectionModule.class);

  @Override
  protected void configure() {
    
    LarusCommAdapterConfiguration configuration
        = getConfigBindingProvider().get(LarusCommAdapterConfiguration.PREFIX,
                                         LarusCommAdapterConfiguration.class);
    
    if (!configuration.enable()) {
      LOG.info("Larus communication adapter disabled by configuration.");
      return;
    }
    
    install(new FactoryModuleBuilder().build(LarusAdapterComponentsFactory.class));
    vehicleCommAdaptersBinder().addBinding().to(LarusCommAdapterFactory.class);
  }
}
