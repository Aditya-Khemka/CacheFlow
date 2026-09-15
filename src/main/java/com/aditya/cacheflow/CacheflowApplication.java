package com.aditya.cacheflow;

import com.aditya.cacheflow.service.CacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import picocli.CommandLine;

import java.io.File;
import java.util.*;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class CacheflowApplication {

	//we need the port before application starts
	//this is to avoid clashes with tomcat later on
	private static int extractPort(String[] args) {
		for (int i = 0; i < args.length; i++) {
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

        //Step 1: Extract port before Spring starts ; set in application properties
		int port = extractPort(args);
        System.setProperty("server.port", String.valueOf(port));

        //Step 2: Start Spring
        SpringApplication app = new SpringApplication(CacheflowApplication.class);
		ConfigurableApplicationContext context = app.run(args); //context holds the spring container
		CommandLine.IFactory factory = context.getBean(CommandLine.IFactory.class);
        final Logger log = LoggerFactory.getLogger(CacheflowApplication.class);

		//now run the CLI command (Picocli)
		CachingProxyCommand command = context.getBean(CachingProxyCommand.class);
		Object obj = new CommandLine(command, factory).execute(args); //obj is never used ; just captured here

        //handle clear-cache
		if (command.isClearCache()) {
			// delete the file directly (no need to build the cache just to clear it)
			File cacheFile = new File("cache.json");

			if (cacheFile.exists()) {
				cacheFile.delete();
				log.info("Cache file deleted.");
			} else {
				log.info("No cache file found — nothing to clear.");
			}

			context.close();
			System.exit(0);
		}

        //handle improper cli
		if (command.getOrigin() == null || command.getOrigin().isBlank()) {
			log.info("Error: --origin is required.");
			context.close();
			System.exit(1);
		}

		//explicitly create the cache ; build Caffeine with real TTL values and restore valid entries from previous session
		CacheService cacheService = context.getBean(CacheService.class);
		cacheService.createCache();
		cacheService.restoreFromFile();

        //All the CLI options are provided and cache is ready
		log.info("Proxy running on port : {}", port);
		log.info("Forwarding to         : {}", command.getOrigin());
		log.info("TTL                   : {} minutes", command.getTtl());
		log.info("Max entries           : {}", command.getMaxEntries());
		System.out.println("\n\n");
	}

}
