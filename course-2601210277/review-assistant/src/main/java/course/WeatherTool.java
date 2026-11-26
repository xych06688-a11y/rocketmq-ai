package course;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
public final class WeatherTool {
    private WeatherTool() { }
    public static JsonNode fetch(String input, Config config) throws Exception {
        JsonNode request=Model.JSON.readTree(input);
        double lat=request.path("latitude").asDouble(Double.NaN), lon=request.path("longitude").asDouble(Double.NaN);
        if (!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180) throw new IllegalArgumentException("Valid coordinates required");
        String snapshot=config.optional("weather.snapshot");
        if(!snapshot.isEmpty()) {
            JsonNode cached=Model.JSON.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(snapshot)));
            long age=java.time.Duration.between(java.time.Instant.parse(cached.path("retrievedAt").asText()),java.time.Instant.now()).toSeconds();
            if(age<0||age>21600) throw new IllegalArgumentException("Weather snapshot must be less than six hours old");
            if(Math.abs(cached.path("requestedLatitude").asDouble()-lat)>0.01||Math.abs(cached.path("requestedLongitude").asDouble()-lon)>0.01) throw new IllegalArgumentException("Weather snapshot coordinates mismatch");
            return cached;
        }
        String url="https://api.open-meteo.com/v1/forecast?latitude="+lat+"&longitude="+lon+"&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=Asia%2FShanghai&forecast_days=3";
        var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(35)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200) throw new IllegalStateException("Weather HTTP "+response.statusCode());
        var result=Model.JSON.createObjectNode(); result.put("source",url);result.put("retrievedAt",java.time.Instant.now().toString());result.set("forecast",Model.JSON.readTree(response.body())); return result;
    }
}
