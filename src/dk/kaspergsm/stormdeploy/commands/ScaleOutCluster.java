package dk.kaspergsm.stormdeploy.commands;

import static org.jclouds.scriptbuilder.domain.Statements.exec;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.RunScriptOnNodesException;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.NodeMetadata.Status;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.options.RunScriptOptions;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.scriptbuilder.domain.Statement;
import org.jclouds.scriptbuilder.domain.StatementList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dk.kaspergsm.stormdeploy.Tools;
import dk.kaspergsm.stormdeploy.configurations.NodeConfiguration;
import dk.kaspergsm.stormdeploy.userprovided.Configuration;
import dk.kaspergsm.stormdeploy.userprovided.Credential;

public class ScaleOutCluster {
	private static Logger log = LoggerFactory.getLogger(ScaleOutCluster.class);
	
	/**
	 * Assumes we are currently attached to the cluster to extend
	 */
	@SuppressWarnings("unchecked")
	public static void AddWorkers(int numInstances, String clustername, String instanceType, Configuration config, Credential credentials, ComputeServiceContext computeContext) {	
		
		/**
		 * Parse current running nodes for cluster
		 */
		ArrayList<NodeMetadata> existingZookeeper = new ArrayList<NodeMetadata>();
		ArrayList<NodeMetadata> existingWorkers = new ArrayList<NodeMetadata>();
		ArrayList<NodeMetadata> existingDRPC = new ArrayList<NodeMetadata>();
		NodeMetadata nimbus = null, ui = null;
		String image = null, region = null;
		for (NodeMetadata n : (Set<NodeMetadata>) computeContext.getComputeService().listNodes()) {			
			if (n.getStatus() != Status.TERMINATED &&
					n.getGroup() != null &&
					n.getGroup().toLowerCase().equals(clustername.toLowerCase()) &&
					n.getUserMetadata().containsKey("daemons")) {
				String daemons = n.getUserMetadata().get("daemons").replace("[", "").replace("]", "");
				
				for (String daemon : daemons.split(",")) {
					if (daemon.trim().toLowerCase().equals("master"))
						nimbus = n;
					if (daemon.trim().toLowerCase().equals("ui"))
						ui = n;
					if (daemon.trim().toLowerCase().equals("worker"))
						existingWorkers.add(n);
					if (daemon.trim().toLowerCase().equals("zk"))
						existingZookeeper.add(n);
					if (daemon.trim().toLowerCase().equals("drpc"))
						existingDRPC.add(n);
				}
				
				if (image == null)
					image = n.getImageId();
				if (region == null)
					region = n.getLocation().getParent().getId();
			}
		}
		
		
		/**
		 * Start new workernodes
		 */
		Set<NodeMetadata> newWorkerNodes = startWorkerNodesNow(
				clustername, 
				NodeConfiguration.getRootCommands(
						clustername,
						credentials, 
						config, 
						getInstancesPrivateIp(existingZookeeper), 
						getInstancesPrivateIp(existingDRPC), 
						nimbus.getPrivateAddresses().iterator().next(), 
						ui.getPrivateAddresses().iterator().next()), 
					instanceType, 
					numInstances, 
					image, 
					region, 
					computeContext,
					config);		

		for (NodeMetadata nm: newWorkerNodes) {
			computeContext.getComputeService().runScriptOnNode(nm.getId(), new StatementList(NodeConfiguration.getCommands(
					clustername,
					credentials,
					config,
					getInstancesPrivateIp(existingZookeeper), 
					getInstancesPrivateIp(existingDRPC), 
					nimbus.getPrivateAddresses().iterator().next(), 
					ui.getPrivateAddresses().iterator().next())),				
					new RunScriptOptions()
					.nameTask("Setup-User")
				 	.overrideLoginCredentials(Tools.getPrivateKeyCredentials(config))
				 	.wrapInInitScript(true)
				 	.overrideLoginUser(config.getImageUsername())
				 	.blockOnComplete(true)
				 	.runAsRoot(false));
		}
		
		/**
		 * Update attachment
		 */
		Attach.attach(clustername, computeContext);
		
		/**
		 * Print final info
		 */
		log.info("User: " + config.getImageUsername());
		log.info("Started:");
		for (NodeMetadata n : newWorkerNodes)
			log.info("\t" + n.getPublicAddresses().iterator().next() + "\t" + "[WORKER]");
		
		
		/**
		 * Close application now
		 */
		System.exit(0);
	}
	
	private static List<String> getInstancesPrivateIp(ArrayList<NodeMetadata> nodes) {
		ArrayList<String> newNodes = new ArrayList<String>();
		for (NodeMetadata n : nodes)
			newNodes.add(n.getPrivateAddresses().iterator().next());
		return newNodes;
	}
	
	@SuppressWarnings("unchecked")
	private static Set<NodeMetadata> startWorkerNodesNow(String clustername, List<Statement> commands, String instanceType, int numInstances, String image, String region, ComputeServiceContext computeContext, Configuration config) {
		try {
			
			// Create initScript
			ArrayList<Statement> initScript = new ArrayList<Statement>();
			initScript.add(exec("echo WORKER > /home/"+config.getImageUsername()+"/daemons"));
			initScript.add(exec("echo \"" + instanceType.toString() + "\" > /home/"+config.getImageUsername()+".instance-type"));
			initScript.addAll(commands);
			
			log.info("Starting " + numInstances + " instance(s) of type " + instanceType);
			TemplateBuilder templateBuilder = computeContext.getComputeService().templateBuilder()
					.hardwareId(instanceType)
					.imageId(image)
					.locationId(region)
					.options(new TemplateOptions()
						.nameTask("Setup")	
						.blockOnComplete(true)
						.wrapInInitScript(true)
						.inboundPorts(22, 6627, 8080) // 22 = SSH, 6627 = Thrift, 8080 = UI
						.overrideLoginUser(config.getImageUsername())
						.userMetadata("daemons", "[WORKER]")
						.runScript(new StatementList(initScript))
						.overrideLoginCredentials(Tools.getPrivateKeyCredentials(config))
						.authorizePublicKey(Tools.getPublicKey(config)));
			return (Set<NodeMetadata>) computeContext.getComputeService().createNodesInGroup(clustername, numInstances, templateBuilder.build());
		} catch (IllegalArgumentException ex) {
			log.error("Error when starting instance(s)", ex);
		} catch (RunNodesException ex) {
			log.error("Error when starting instance(s)", ex);
		}
		return null;
	}
}
