package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.Task;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;


@Extension
public class ThrottleQueueTaskDispatcher extends QueueTaskDispatcher {

    @Override
    public CauseOfBlockage canTake(Node node, Task task) {
        if (task instanceof MatrixConfiguration) {
            return null;
        }
            
        ThrottleJobProperty tjp = getThrottleJobProperty(task);
        if (tjp!=null && tjp.getThrottleEnabled()) {
            CauseOfBlockage cause = canRun(task, tjp);
            if (cause != null) return cause;

            if (tjp.getThrottleOption().equals("project")) {
                if (tjp.getMaxConcurrentPerNode().intValue() > 0) {
                    int maxConcurrentPerNode = tjp.getMaxConcurrentPerNode().intValue();
                    int runCount = buildsOfProjectOnNode(node, task);
                    
                    // This would mean that there are as many or more builds currently running than are allowed.
                    if (runCount >= maxConcurrentPerNode) {
                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                    }
                }
            }
            else if (tjp.getThrottleOption().equals("category")) {
                // If the project is in one or more categories...
                if (tjp.getCategoryConfigurations() != null && !tjp.getCategoryConfigurations().isEmpty()) {
                    for (ThrottleJobProperty.CategoryConfiguration catCfg : tjp.getCategoryConfigurations()) {
                        // Quick check that catNm itself is a real string.
                        if (catCfg != null && catCfg.getCategoryName() != null && !catCfg.getCategoryName().equals("")) {
                            String catNm = catCfg.getCategoryName();
                            List<AbstractProject<?,?>> categoryProjects = getCategoryProjects(catNm);
                            
                            ThrottleJobProperty.ThrottleCategory category =
                                ((ThrottleJobProperty.DescriptorImpl)tjp.getDescriptor()).getCategoryByName(catNm);
                            
                            // Double check category itself isn't null
                            if (category != null) {
                                // Max concurrent per node for category
                                if (category.getMaxConcurrentPerNode().intValue() > 0) {
                                    int maxConcurrentPerNode = category.getMaxConcurrentPerNode().intValue();
                                    int runCount = 0;
                                    
                                    for (AbstractProject<?,?> catProj : categoryProjects) {
                                        if (Hudson.getInstance().getQueue().isPending(catProj)) {
                                            return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                                        }
                                        runCount += buildsOfProjectOnNode(node, catProj);
                                    }
                                    // This would mean that there are as many or more builds currently running than are allowed.
                                    if (runCount >= maxConcurrentPerNode) {
                                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    // @Override on jenkins 4.127+ , but still compatible with 1.399
    public CauseOfBlockage canRun(Queue.Item item) {
        ThrottleJobProperty tjp = getThrottleJobProperty(item.task);
        if (tjp!=null && tjp.getThrottleEnabled()) {
            return canRun(item.task, tjp);
        }
        return null;
    }

    public CauseOfBlockage canRun(Task task, ThrottleJobProperty tjp) {
        if (task instanceof MatrixConfiguration) {
            return null;
        }
        if (Hudson.getInstance().getQueue().isPending(task)) {
            return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
        }
        if (tjp.getThrottleOption().equals("project")) {
            if (tjp.getMaxConcurrentTotal().intValue() > 0) {
                int maxConcurrentTotal = tjp.getMaxConcurrentTotal().intValue();
                int totalRunCount = buildsOfProjectOnAllNodes(task);
                
                if (totalRunCount >= maxConcurrentTotal) {
                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalRunCount));
                }
            }
        }
        // If the project is in one or more categories...
        else if (tjp.getThrottleOption().equals("category")) {
            if (tjp.getCategoryConfigurations() != null && !tjp.getCategoryConfigurations().isEmpty()) {
                for (ThrottleJobProperty.CategoryConfiguration catCfg : tjp.getCategoryConfigurations()) {
                    // Quick check that catNm itself is a real string.
                    if (catCfg != null && catCfg.getCategoryName() != null && !catCfg.getCategoryName().equals("")) {
                        String catNm = catCfg.getCategoryName();
                        List<AbstractProject<?,?>> categoryProjects = getCategoryProjects(catNm);
                        
                        ThrottleJobProperty.ThrottleCategory category =
                            ((ThrottleJobProperty.DescriptorImpl)tjp.getDescriptor()).getCategoryByName(catNm);
                        
                        // Double check category itself isn't null
                        if (category != null) {
                            if (category.getMaxConcurrentTotal().intValue() > 0) {
                                int maxConcurrentTotal = category.getMaxConcurrentTotal().intValue();
                                int totalRunCount = 0;
                                boolean writer_locked = false;
                                
                                for (AbstractProject<?,?> catProj : categoryProjects) {
                                    if (Hudson.getInstance().getQueue().isPending(catProj)) {
                                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                                    }
                                    int running = buildsOfProjectOnAllNodes(catProj);
                                    if (running > 0 && isCategoryWriter(catProj, catNm)) {
                                        writer_locked = true;
                                    }
                                    totalRunCount += running;
                                }
                                
                                if (totalRunCount >= maxConcurrentTotal) {
                                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalRunCount));
                                }
                                else if (catCfg.getCategoryType().equals("writer") && writer_locked) {
                                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_WriterLock());
                                }
                                else if (catCfg.getCategoryType().equals("writer") && totalRunCount > 0) {
                                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_WriterLock());
                                }
                                else if (catCfg.getCategoryType().equals("reader") && writer_locked) {
                                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_ReaderLock());
                                }
                            }
                            
                        }
                    }
                }
            }
        }

        return null;
    }

    private boolean isCategoryWriter(Task task, String categoryName) {
        ThrottleJobProperty tjp = getThrottleJobProperty(task);

        for (ThrottleJobProperty.CategoryConfiguration catCfg : tjp.getCategoryConfigurations()) {
            if (catCfg.getCategoryName().equals(categoryName) && catCfg.getCategoryType().equals("writer")) {
                return true;
            }
        }

        return false;
    }

    private ThrottleJobProperty getThrottleJobProperty(Task task) {
        if (task instanceof AbstractProject) {
            AbstractProject<?,?> p = (AbstractProject<?,?>) task;
            if (task instanceof MatrixConfiguration) {
                p = (AbstractProject<?,?>)((MatrixConfiguration)task).getParent();
            }
            ThrottleJobProperty tjp = p.getProperty(ThrottleJobProperty.class);
            return tjp;
        }
        return null;
    }
    
    private int buildsOfProjectOnNode(Node node, Task task) {
        int runCount = 0;
        LOGGER.fine("Checking for builds of " + task.getName() + " on node " + node.getDisplayName());
        
        // I think this'll be more reliable than job.getBuilds(), which seemed to not always get
        // a build right after it was launched, for some reason.
        for (Executor e : node.toComputer().getExecutors()) {
            runCount += buildsOnExecutor(task, e);
        }
        if (task instanceof MatrixProject) {
            for (Executor e : node.toComputer().getOneOffExecutors()) {
                runCount += buildsOnExecutor(task, e);
            }
        }
        
        return runCount;
    }

    private int buildsOfProjectOnAllNodes(Task task) {
        int totalRunCount = 0;
        
        for (Computer c : Hudson.getInstance().getComputers()) {
            for (Executor e : c.getExecutors()) {
                totalRunCount += buildsOnExecutor(task, e);
            }
            
            if (task instanceof MatrixProject) {
                for (Executor e : c.getOneOffExecutors()) {
                    totalRunCount += buildsOnExecutor(task, e);
                }
            }
        }

        return totalRunCount;
    }

    private int buildsOnExecutor(Task task, Executor exec) {
        int runCount = 0;
        if (exec.getCurrentExecutable() != null
            && exec.getCurrentExecutable().getParent() == task) {
            runCount++;
        }

        return runCount;
    }
        
        
    private List<AbstractProject<?,?>> getCategoryProjects(String category) {
        List<AbstractProject<?,?>> categoryProjects = new ArrayList<AbstractProject<?,?>>();

        if (category != null && !category.equals("")) {
            for (AbstractProject<?,?> p : Hudson.getInstance().getAllItems(AbstractProject.class)) {
                ThrottleJobProperty t = p.getProperty(ThrottleJobProperty.class);
                
                if (t!=null && t.getThrottleEnabled() && t.getCategoryConfigurations() != null) {
                    for (ThrottleJobProperty.CategoryConfiguration catCfg : t.getCategoryConfigurations()) {
                        String catNam = catCfg.getCategoryName();
                        if (catNam != null && catNam.equals(category)) {
                            categoryProjects.add(p);
                        }
                    }
                }
            }
        }
        
        return categoryProjects;
    }

    private static final Logger LOGGER = Logger.getLogger(ThrottleQueueTaskDispatcher.class.getName());

}
                
                    