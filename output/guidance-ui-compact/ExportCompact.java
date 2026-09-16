import kr.co.hanium.dreamup.walksafe.navigation.CompactGuidancePresentationPolicy;
import java.util.Base64;
public class ExportCompact {
 public static void main(String[] args) {
  String message = new String(Base64.getDecoder().decode(args[0]), java.nio.charset.StandardCharsets.UTF_8);
  var p = CompactGuidancePresentationPolicy.INSTANCE.present(message, Boolean.parseBoolean(args[1]), Boolean.parseBoolean(args[2]));
  System.out.println(p.getSymbol());
  System.out.println(Base64.getEncoder().encodeToString(p.getText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
 }
}
