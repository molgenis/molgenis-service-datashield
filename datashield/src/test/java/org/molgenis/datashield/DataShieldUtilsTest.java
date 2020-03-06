package org.molgenis.datashield;

import static org.junit.Assert.*;
import static org.molgenis.datashield.DataShieldUtils.createRawResponse;

import org.junit.Test;
import org.rosuda.REngine.REXPMismatchException;
import org.rosuda.REngine.REXPRaw;

public class DataShieldUtilsTest {
  @Test
  public void testSerializeCommand() {
    String cmd = "meanDS(D$age";
    String serializedCommand = DataShieldUtils.serializeCommand(cmd);
    assertEquals("try(serialize({meanDS(D$age}, NULL))", serializedCommand);
  }

  @Test
  public void testCreateRawResponse() throws REXPMismatchException {
    REXPRaw rexpDouble = new REXPRaw(new byte[0]);
    byte[] actual = createRawResponse(rexpDouble);
    byte[] expected = new byte[0];
    assertArrayEquals(expected, actual);
  }
}