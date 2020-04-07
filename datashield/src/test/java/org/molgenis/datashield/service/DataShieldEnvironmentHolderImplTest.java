package org.molgenis.datashield.service;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.molgenis.r.RConnectionFactory;
import org.molgenis.r.model.Package;
import org.molgenis.r.service.PackageService;
import org.obiba.datashield.core.DSEnvironment;
import org.obiba.datashield.core.DSMethod;
import org.obiba.datashield.core.DSMethodType;
import org.rosuda.REngine.REXPMismatchException;
import org.rosuda.REngine.Rserve.RConnection;
import org.rosuda.REngine.Rserve.RserveException;

@ExtendWith(MockitoExtension.class)
class DataShieldEnvironmentHolderImplTest {

  @Mock RConnectionFactory rConnectionFactory;
  @Mock PackageService packageService;
  private DataShieldEnvironmentHolderImpl environmentHolder;

  @BeforeEach
  public void beforeEach() {
    environmentHolder = new DataShieldEnvironmentHolderImpl(packageService, rConnectionFactory);
  }

  @Test
  public void testGetAggregateEnvironment() throws REXPMismatchException, RserveException {
    populateEnvironment(
        ImmutableSet.of("scatterPlotDs", "is.character=base::is.character"), ImmutableSet.of());

    DSEnvironment environment = environmentHolder.getEnvironment(DSMethodType.AGGREGATE);

    assertEquals(
        environment.getMethods().stream().map(DSMethod::getName).collect(Collectors.toList()),
        asList("scatterPlotDs", "is.character"));
  }

  @Test
  public void testGetAssignEnvironment() throws REXPMismatchException, RserveException {
    populateEnvironment(ImmutableSet.of(), ImmutableSet.of("meanDS", "dim=base::dim"));

    DSEnvironment environment = environmentHolder.getEnvironment(DSMethodType.ASSIGN);

    assertEquals(
        environment.getMethods().stream().map(DSMethod::getName).collect(Collectors.toList()),
        asList("meanDS", "dim"));
  }

  @Test
  public void testPopulateIllegalMethodName() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            populateEnvironment(
                ImmutableSet.of("method=base::method=base::method"), ImmutableSet.of()));
  }

  @Test
  public void testPopulateDuplicateMethodName() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            populateEnvironment(
                ImmutableSet.of("dim=base::dim", "dim=other::dim"), ImmutableSet.of()));
  }

  private void populateEnvironment(
      ImmutableSet<String> aggregateMethods, ImmutableSet<String> assignMethods)
      throws REXPMismatchException, RserveException {
    RConnection rConnection = mock(RConnection.class);
    when(rConnectionFactory.retryCreateConnection()).thenReturn(rConnection);

    Package pack =
        Package.builder()
            .setAggregateMethods(aggregateMethods)
            .setAssignMethods(assignMethods)
            .setLibPath("test")
            .setVersion("test")
            .setName("test")
            .setBuilt("test")
            .build();

    when(packageService.getInstalledPackages(rConnection)).thenReturn(singletonList(pack));

    environmentHolder.populateEnvironments();
    verify(rConnection).close();
  }
}