package com.aditya.cacheflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine;

import java.util.*;

@SpringBootApplication
public class CacheflowApplication {

	//we need the port before application starts
	//this is to avoid clashes with tomcat later on
	private static int extractPort(String[] args) {
		for (int i = 0; i < args.length - 1; i++) {
			if ("--port".equals(args[i])) {
				try {
					return Integer.parseInt(args[i + 1]);
				} catch (NumberFormatException e) {
					System.err.println("Invalid port value: " + args[i + 1]);
					System.exit(1);
				}
			}
		}
		return 8080; // default if --port not provided
	}

	public static void main(String[] args) {
		int port = extractPort(args);

		SpringApplication app = new SpringApplication(CacheflowApplication.class);

		//setup server for tomcat
		app.setDefaultProperties(Map.of("server.port", String.valueOf(port)));

		ConfigurableApplicationContext context = app.run(args);

		//now run the CLI command
		CachingProxyCommand command = context.getBean(CachingProxyCommand.class);
		CommandLine.IFactory factory = context.getBean(CommandLine.IFactory.class);
		Object obj = new CommandLine(command, factory).execute(args);


		if (command.isClearCache()) {
			System.out.println("Cache cleared.");
			context.close(); // Spring shutdown
			System.exit(-1);
		}

		if (command.getOrigin() == null || command.getOrigin().isBlank()) {
			System.err.println("Error: --origin is required. Use --origin <url>");
			context.close();
			System.exit(1);
		}

		System.out.println("Proxy running on port " + port);
		System.out.println("Forwarding to: " + command.getOrigin());

	}

}
