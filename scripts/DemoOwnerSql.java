import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.springframework.security.crypto.bcrypt.BCrypt;

// Run manually in a local terminal. Never connects to the database.
class DemoOwnerSql {
  private static String literal(String value) {
    return "CONVERT(0x" + HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8))
        + " USING utf8mb4)";
  }

  static String sql(String email, String name, String hash) {
    return "START TRANSACTION;\n"
        + "INSERT INTO together_market.users (email,password_hash,name,role)\n"
        + "VALUES (" + literal(email) + "," + literal(hash) + "," + literal(name) + ",'OWNER');\n"
        + "SELECT id,role FROM together_market.users WHERE email=" + literal(email) + ";\n"
        + "COMMIT;\n";
  }

  public static void main(String[] args) {
    if (args.length == 1 && args[0].equals("--self-test")) {
      String hash = BCrypt.hashpw("test-value-only", BCrypt.gensalt());
      if (!BCrypt.checkpw("test-value-only", hash)
          || BCrypt.checkpw("different-test-value", hash)
          || !sql("owner@example.invalid", "점주", hash).contains("'OWNER'"))
        throw new IllegalStateException("Self-test failed");
      System.out.println("BCrypt and SQL generation: PASS (no account created)");
      return;
    }
    Console console = System.console();
    if (console == null) {
      System.err.println("일반 PowerShell 터미널에서 실행하세요. IntelliJ Run 창에서는 실행하지 마세요.");
      System.exit(1);
      return;
    }
    char[] password = null, confirmation = null;
    try {
      String email = console.readLine("App owner email: ");
      String name = console.readLine("App owner display name: ");
      if (email == null || name == null || email.isBlank() || !email.contains("@")
          || email.codePointCount(0,email.length()) > 255
          || name.isBlank() || name.codePointCount(0,name.length()) > 100)
        throw new IllegalArgumentException();
      password = console.readPassword("New app password (hidden): ");
      confirmation = console.readPassword("Repeat app password (hidden): ");
      if (password == null || confirmation == null || password.length == 0
          || !Arrays.equals(password,confirmation)
          || new String(password).getBytes(StandardCharsets.UTF_8).length > 72)
        throw new IllegalArgumentException();
      String hash = BCrypt.hashpw(new String(password), BCrypt.gensalt());
      String statement = sql(email.strip(),name.strip(),hash);
      Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(statement),null);
      System.out.println("SQL copied to clipboard. Paste into a new Workbench SQL tab and execute.");
      System.out.println("No database connection was made. Do not share the clipboard SQL.");
    } catch (Exception exception) {
      System.err.println("계정 입력값·비밀번호 일치 여부(UTF-8 72바이트 이하) 또는 클립보드 사용 가능 여부를 확인하세요.");
      System.exit(1);
    } finally {
      if (password != null) Arrays.fill(password,'\0');
      if (confirmation != null) Arrays.fill(confirmation,'\0');
    }
  }
}