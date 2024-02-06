package org.jenkinsci.plugins.pluginusage.analyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import hudson.PluginWrapper;
import hudson.model.Item;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.pluginusage.JobsPerPlugin;

public class JobCollector {

    private static final Logger LOGGER = Logger.getLogger(org.jenkinsci.plugins.pluginusage.analyzer.JobCollector.class.getName());

    private Map<PluginWrapper, JobsPerPlugin> mapJobsPerPlugin;

    public Map<PluginWrapper, JobsPerPlugin> getJobsPerPlugin(){
        return getJobsPerPlugin(a -> {}, a -> {});
    }

    public Map<PluginWrapper, JobsPerPlugin> getJobsPerPlugin(
            Consumer<Map.Entry<Item, Set<PluginWrapper>>> dataConsumer,
            Consumer<Double> progressConsumer)
    {
        if (mapJobsPerPlugin != null){
            return mapJobsPerPlugin;
        }

        mapJobsPerPlugin = new HashMap<>();

        List<AbstractProjectAnalyzer> analyzers =
                Arrays.asList(
                        new ProjectAnalyzer(),
                        new MavenProjectAnalyzer(),
                        new PipelineProjectAnalyzer(),
                        new MatrixProjectAnalyzer(),
                        new ComputedFolderAnalyzer(),
                        new PipelineLastBuildAnalyzer());

        // bootstrap map with all job related plugins
        for(AbstractProjectAnalyzer analyzer: analyzers)
        {
            try{
                for(PluginWrapper plugin: analyzer.getPlugins()){
                    if (plugin != null){
                        if (mapJobsPerPlugin.get(plugin) == null) {
                            mapJobsPerPlugin.put(plugin, new JobsPerPlugin(plugin));
                        }
                    }
                }
            } catch(Exception e){
                LOGGER.log(Level.WARNING, "Exception caught: " + e , e);
            }
        }

        final List<Item> items = Jenkins.get().getAllItems()
                .stream()
                .filter(job -> !analyzers
                        .stream()
                        .map(analyzer -> analyzer.ignoreJob(job))
                        .reduce(false, (a, b) -> a || b))
                .collect(Collectors.toList());
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            final Set<PluginWrapper> pluginsFromItem = new HashSet<>();

            for (AbstractProjectAnalyzer analyzer : analyzers) {
                try {
                    final var pluginWrapperSet = analyzer.getPluginsFromItem(item);
                    pluginsFromItem.addAll(pluginWrapperSet.stream().filter(Objects::nonNull).collect(Collectors.toSet()));
                    for (PluginWrapper plugin : pluginWrapperSet) {
                        addItem(item, plugin);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Exception caught in job " + item.getFullName() + ": " + e, e);
                } catch (Throwable e) {
                    LOGGER.log(Level.SEVERE, "Exception caught in job " + item.getFullName() + ": " + e, e);
                }
            }

            dataConsumer.accept(Map.entry(item, pluginsFromItem));
            progressConsumer.accept((1.0 + i) / items.size());
        }

        return mapJobsPerPlugin;
    }

    protected void addItem(Item item, PluginWrapper usedPlugin) {
        if (usedPlugin != null) {
            JobsPerPlugin jobsPerPlugin = mapJobsPerPlugin.get(usedPlugin);
            if (jobsPerPlugin != null) {
                jobsPerPlugin.addProject(item);
            } else {
                JobsPerPlugin jobsPerPlugin2 = new JobsPerPlugin(usedPlugin);
                jobsPerPlugin2.addProject(item);
                mapJobsPerPlugin.put(usedPlugin, jobsPerPlugin2);
            }
        }
    }

    public int getNumberOfJobs() {
        return getJobsPerPlugin()
                .values()
                .stream()
                .flatMap(jobsPerPlugin -> jobsPerPlugin.getProjects().stream())
                .collect(Collectors.toSet())
                .size();
    }

    public List<PluginWrapper> getOtherPlugins() {
        List<PluginWrapper> allPlugins = Jenkins.get().getPluginManager().getPlugins();
        List<PluginWrapper> others = new ArrayList<>(allPlugins);

        others.removeAll(getJobsPerPlugin().keySet());

        return others;
    }

}
