package okhttp3;
import java.io.IOException;
public interface Call { Response execute() throws IOException; void cancel(); }
