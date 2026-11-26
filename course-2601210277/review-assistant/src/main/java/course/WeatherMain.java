package course;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public final class WeatherMain {
    public static void main(String[] args) throws Exception {
        if(args.length<2) throw new IllegalArgumentException("worker|plan config [city latitude longitude]");
        Config config=new Config(Path.of(args[1]));
        var topics=Map.of("weather","WeatherAgentTask_"+Config.ID,"travel","TravelAgentTask_"+Config.ID);
        try(Bus bus=new Bus(config,topics,"WorkerAgentResponse_"+Config.ID,"weather_supervisor")) {
            if("worker".equals(args[0])) {
                Model model=new Model(config);bus.startWorker("weather",model);bus.startWorker("travel",model);
                System.out.println("WEATHER_WORKERS_READY student="+Config.ID);new CountDownLatch(1).await();
            } else if("plan".equals(args[0])&&args.length==5) {
                String session=Config.ID+"-weather-"+UUID.randomUUID();bus.subscribeSession(session);
                String request=Model.JSON.writeValueAsString(Map.of("city",args[2],"latitude",Double.parseDouble(args[3]),"longitude",Double.parseDouble(args[4]),"request","查询明天天气并安排一天行程"));
                var weather=Protocol.result(bus.send("weather",session,request).get(125,TimeUnit.SECONDS));
                var travel=Protocol.result(bus.send("travel",session,Model.JSON.writeValueAsString(Map.of("request",request,"weather",weather))).get(125,TimeUnit.SECONDS));
                Path out=Path.of("results",session+".json");Files.createDirectories(out.getParent());
                Model.JSON.writerWithDefaultPrettyPrinter().writeValue(out.toFile(),Map.of("student",Config.ID,"name","常何小丫","session",session,"weather",weather,"travel",travel,"mode","cloud-model-and-rocketmq-real-weather-api"));
                System.out.println("WEATHER_TRAVEL_COMPLETE file="+out.toAbsolutePath());
            } else throw new IllegalArgumentException("Invalid arguments");
        }
    }
}
