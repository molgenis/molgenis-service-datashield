package org.molgenis.datashield.service;

import org.obiba.datashield.core.DSEnvironment;
import org.obiba.datashield.core.DSMethodType;

public interface DataShieldEnvironmentHolder {

  DSEnvironment getEnvironment(DSMethodType dsMethodType);
}
