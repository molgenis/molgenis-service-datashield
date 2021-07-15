package org.molgenis.armadillo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.molgenis.armadillo.exceptions.ExpressionException;
import org.obiba.datashield.core.DSEnvironment;
import org.obiba.datashield.core.DSMethod;
import org.obiba.datashield.core.DSMethodType;
import org.obiba.datashield.core.NoSuchDSMethodException;
import org.obiba.datashield.core.impl.DefaultDSMethod;
import org.obiba.datashield.r.expr.v1.ParseException;

@ExtendWith(MockitoExtension.class)
class ExpressionRewriterImplTest {

  private ExpressionRewriterImpl expressionRewriter;

  @Mock private DataShieldEnvironmentHolder environmentHolder;
  @Mock private DSEnvironment mockEnvironment;

  @BeforeEach
  void beforeEach() throws ParseException {
    when(environmentHolder.getEnvironment(any(DSMethodType.class))).thenReturn(mockEnvironment);
    expressionRewriter = new ExpressionRewriterImpl(environmentHolder);
  }

  @Test
  void testRewriteAssignDsBase() {
    DSMethod meanDS = new DefaultDSMethod("meanDS", "dsBase::meanDS");
    when(mockEnvironment.getMethod("meanDS")).thenReturn(meanDS);
    when(mockEnvironment.getMethodType()).thenReturn(DSMethodType.ASSIGN);
    assertEquals(".ASSIGN$meanDS(D$age)", expressionRewriter.rewriteAssign("meanDS(D$age)"));
  }

  @Test
  void testRewriteAssignNonDsBase() {
    DSMethod dim = new DefaultDSMethod("dim", "base::dim");
    when(mockEnvironment.getMethod("dim")).thenReturn(dim);
    when(mockEnvironment.getMethodType()).thenReturn(DSMethodType.ASSIGN);
    assertEquals(".ASSIGN$dim(x, y)", expressionRewriter.rewriteAssign("dim(x, y)"));
  }

  @Test
  void testRewriteAssignUnknown() {
    when(mockEnvironment.getMethod("banana")).thenThrow(NoSuchDSMethodException.class);
    assertThrows(
        ExpressionException.class, () -> expressionRewriter.rewriteAggregate("banana(x,y)"));
  }

  @Test
  void testRewriteAggregateDsBase() {
    DSMethod dim = new DefaultDSMethod("scatterPlotDs", "dsBase::scatterPlotDs");
    when(mockEnvironment.getMethod("scatterPlotDs")).thenReturn(dim);
    when(mockEnvironment.getMethodType()).thenReturn(DSMethodType.AGGREGATE);
    assertEquals(
        ".AGGREGATE$scatterPlotDs(D$age, D$potatoes_a_day)",
        expressionRewriter.rewriteAggregate("scatterPlotDs(D$age, D$potatoes_a_day)"));
  }

  @Test
  void testRewriteAggregateNonDsBase() {
    DSMethod dim = new DefaultDSMethod("is.character", "base::is.character");
    when(mockEnvironment.getMethod("is.character")).thenReturn(dim);
    when(mockEnvironment.getMethodType()).thenReturn(DSMethodType.AGGREGATE);
    assertEquals(
        ".AGGREGATE$is.character(3)", expressionRewriter.rewriteAggregate("is.character(3)"));
  }

  @Test
  void testRewriteFaultyExpression() {
    assertThrows(
        ExpressionException.class, () -> expressionRewriter.rewriteAggregate(".ASSIGN$meanDS(="));
  }
}
