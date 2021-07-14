package org.molgenis.armadillo.exceptions;

import static java.lang.String.format;

import org.obiba.datashield.core.NoSuchDSMethodException;
import org.obiba.datashield.r.expr.v2.ParseException;
import org.obiba.datashield.r.expr.v2.TokenMgrError;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ExpressionException extends RuntimeException {

  public ExpressionException(String expression, ParseException err) {
    super(format("Error parsing expression '%s':%n%s", expression, err.getMessage()), err);
  }

  public ExpressionException(NoSuchDSMethodException err) {
    super(err.getMessage(), err);
  }

  public ExpressionException(String expression, TokenMgrError e) {
    super(format("Error parsing expression '%s':%n%s", expression, e.getMessage()), e);
  }
}
