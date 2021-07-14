package org.molgenis.armadillo.service;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.molgenis.armadillo.DataShieldProperties;
import org.molgenis.armadillo.exceptions.DuplicateRMethodException;
import org.molgenis.armadillo.exceptions.IllegalRMethodStringException;
import org.molgenis.armadillo.exceptions.IllegalRPackageException;
import org.molgenis.r.RConnectionFactory;
import org.molgenis.r.model.RPackage;
import org.molgenis.r.service.PackageService;
import org.obiba.datashield.core.DSEnvironment;
import org.obiba.datashield.core.DSMethod;
import org.obiba.datashield.core.DSMethodType;
import org.obiba.datashield.core.impl.DefaultDSMethod;
import org.rosuda.REngine.Rserve.RConnection;

@ExtendWith(MockitoExtension.class)
class DataShieldEnvironmentHolderImplTest {

  @Mock RConnectionFactory rConnectionFactory;
  @Mock PackageService packageService;
  @Mock DataShieldProperties dataShieldProperties;
  private DataShieldEnvironmentHolderImpl environmentHolder;

  @BeforeEach
  void beforeEach() {
    environmentHolder =
        new DataShieldEnvironmentHolderImpl(
            packageService, rConnectionFactory, dataShieldProperties);
  }

  @Test
  void testGetAggregateEnvironment() {
    when(dataShieldProperties.getWhitelist()).thenReturn(Set.of("dsBase"));
    populateEnvironment(
        ImmutableSet.of("scatterPlotDs", "is.character=base::is.character"), ImmutableSet.of());

    DSEnvironment environment = environmentHolder.getEnvironment(DSMethodType.AGGREGATE);

    assertEquals(
        asList("scatterPlotDs", "is.character"),
        environment.getMethods().stream().map(DSMethod::getName).collect(toList()));
  }

  @Test
  void testGetAssignEnvironment() {
    when(dataShieldProperties.getWhitelist()).thenReturn(Set.of("dsBase"));
    populateEnvironment(ImmutableSet.of(), ImmutableSet.of("meanDS", "dim=base::dim"));

    DSEnvironment environment = environmentHolder.getEnvironment(DSMethodType.ASSIGN);

    var expected =
        asList(
            new DefaultDSMethod("meanDS", "dsBase::meanDS", "dsBase", "1.2.3"),
            new DefaultDSMethod("dim", "base::dim", "base", null));
    var actual = environment.getMethods();

    // TODO: workaround until equals method implemented in DSMethod:
    Gson gson = new Gson();
    assertEquals(
        expected.stream().map(gson::toJson).collect(toList()),
        actual.stream().map(gson::toJson).collect(toList()));
  }

  @Test
  void testPopulateIllegalMethodName() {
    when(dataShieldProperties.getWhitelist()).thenReturn(Set.of("dsBase"));
    assertThrows(
        IllegalRMethodStringException.class,
        () ->
            populateEnvironment(
                ImmutableSet.of("method=base::method=base::method"), ImmutableSet.of()));
  }

  @Test
  void testPopulateDuplicateMethodName() {
    when(dataShieldProperties.getWhitelist()).thenReturn(Set.of("dsBase"));
    assertThrows(
        DuplicateRMethodException.class,
        () ->
            populateEnvironment(
                ImmutableSet.of("dim=base::dim", "dim=other::dim"), ImmutableSet.of()));
  }

  @Test
  void testPopulateMethodFromNonWhitelistedPackage() {
    when(dataShieldProperties.getWhitelist()).thenReturn(Set.of("otherPackage"));
    assertThrows(
        IllegalRPackageException.class,
        () -> populateEnvironment(ImmutableSet.of("dim=base::dim"), ImmutableSet.of()));
  }

  private void populateEnvironment(
      ImmutableSet<String> aggregateMethods, ImmutableSet<String> assignMethods) {
    RConnection rConnection = mock(RConnection.class);
    when(rConnectionFactory.retryCreateConnection()).thenReturn(rConnection);

    RPackage pack =
        RPackage.builder()
            .setAggregateMethods(aggregateMethods)
            .setAssignMethods(assignMethods)
            .setLibPath("test")
            .setVersion("test")
            .setName("dsBase")
            .setBuilt("test")
            .setVersion("1.2.3")
            .build();

    when(packageService.getInstalledPackages(rConnection)).thenReturn(singletonList(pack));

    environmentHolder.populateEnvironments();
    verify(rConnection).close();
  }
}
