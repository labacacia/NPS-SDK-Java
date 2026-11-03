package com.labacacia.nps.alpha20;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.ConformanceFixtures;
import com.labacacia.nps.core.NpsStatusCodes;
import com.labacacia.nps.ncp.NcpErrorCodes;
import com.labacacia.nps.nwp.NwpErrorCodes;
import com.labacacia.nps.nip.NipErrorCodes;
import com.labacacia.nps.nop.NopErrorCodes;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ErrorStatusTest {
 @Test void sharedMappings() throws Exception {
  var cases = new ObjectMapper().readTree(Files.readString(ConformanceFixtures.resolve("alpha20/error_status_vectors.json"))).get("cases");
  assertEquals(17, cases.size());
  var maps = Map.of("NCP", NcpErrorCodes.NCP_TO_NPS_STATUS, "NWP", NwpErrorCodes.NWP_TO_NPS_STATUS, "NIP", NipErrorCodes.NIP_TO_NPS_STATUS, "NOP", NopErrorCodes.NOP_TO_NPS_STATUS);
  var types = Map.of("NCP", NcpErrorCodes.class, "NWP", NwpErrorCodes.class, "NIP", NipErrorCodes.class, "NOP", NopErrorCodes.class);
  for (var v : cases) {
   String code = v.get("code").asText(), protocol = code.substring(0, 3);
   boolean declared = false;
   for (var field : types.get(protocol).getFields()) if (field.getType() == String.class && code.equals(field.get(null))) declared = true;
   assertTrue(declared, code);
   String actual = maps.get(protocol).get(code);
   assertEquals(v.get("status").asText(), actual, code);
   assertEquals(v.get("http").asInt(), NpsStatusCodes.toHttpStatus(actual), code);
  }
 }
}
