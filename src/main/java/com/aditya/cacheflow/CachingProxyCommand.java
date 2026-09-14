package com.aditya.cacheflow;


import com.aditya.cacheflow.config.AppConfig;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

//mark this class as a CLI command (use picoli for this)
@CommandLine.Command(
        name = "CacheFlow",
        mixinStandardHelpOptions = true,
        description = "A caching proxy server"
)
@Component
@Data
public class CachingProxyCommand implements Runnable{
    //whenever 'port' appears in the CLI, it must be mapped to the int variable 'port'
    @CommandLine.Option(
            names = "--port",
            description = "Port to run the proxy on",
            defaultValue = "8080"
    )
    private int port;

    @CommandLine.Option(
            names = "--origin",
            description = "Origin server URL to forward requests to"
    )
    private String origin;

    @CommandLine.Option(
            names = "--clear-cache",
            description = "Clear the cache and exit",
            defaultValue = "false"
    )
    private boolean clearCache;

    @CommandLine.Option(
            names = "--ttl",
            description = "Cache entry time-to-live, in minutes",
            defaultValue = "15")
    private int ttl;

    @CommandLine.Option(
            names = "--max-entries",
            description = "Maximum number of entries the cache can hold",
            defaultValue = "100")
    private int maxEntries;



    private AppConfig config ;
    public CachingProxyCommand(AppConfig Appconfig) {
        this.config = Appconfig;
    }

    @Override
    public void run (){
        // Called by Picocli after parsing args[]
        config.setPortNo(port);
        config.setOriginUrl(origin);
        config.setTtlMinutes(ttl);
        config.setMaxEntries(maxEntries);
    }
    /*
    After Picocli finishes parsing, it calls run(). This comes from implementing the runnable interface.
    That's where we write the parsed values into AppConfig ; so the rest of the app can read them.
     */
}
