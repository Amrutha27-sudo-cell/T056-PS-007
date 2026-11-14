package com.billing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class App {
    public static void main(String[] args) throws IOException {
        Path resources = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("src", "main", "resources");
        Path commands = args.length > 1 ? Paths.get(args[1]) : resources.resolve("commands.txt");
        Path output = args.length > 2 ? Paths.get(args[2]) : Paths.get("output");

        SimulationEngine engine = SimulationEngine.fromDirectory(resources);
        Map<String, String> results;
        try (InputStream input = Files.newInputStream(commands)) {
            results = engine.execute(input);
        }
        SimulationEngine.writeOutputs(output, results);
        System.out.println("Processed command file: " + commands.toAbsolutePath());
        System.out.println("Wrote " + results.size() + " CSV files to " + output.toAbsolutePath());
    }
}
