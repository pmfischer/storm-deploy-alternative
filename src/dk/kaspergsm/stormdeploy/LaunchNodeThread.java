package dk.kaspergsm.stormdeploy;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import org.jclouds.aws.ec2.compute.AWSEC2TemplateOptions;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.Hardware;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.ec2.EC2Api;
import org.jclouds.ec2.compute.options.EC2TemplateOptions;
import org.jclouds.ec2.domain.Attachment;
import org.jclouds.ec2.domain.Volume;
import org.jclouds.ec2.domain.Volume.Status;
import org.jclouds.scriptbuilder.domain.Statement;
import org.jclouds.scriptbuilder.domain.StatementList;
import static org.jclouds.scriptbuilder.domain.Statements.exec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dk.kaspergsm.stormdeploy.configurations.Zookeeper;
import dk.kaspergsm.stormdeploy.userprovided.Configuration;

/**
 * Used to launch a new nodes
 * 
 * @author Kasper Grud Skat Madsen
 */
public class LaunchNodeThread extends Thread {
	private static Logger log = LoggerFactory.getLogger(LaunchNodeThread.class);
	private Configuration _config;
	private String _instanceType, _clustername, _region, _placementgroup, _image, _username;
	private Set<NodeMetadata> _newNodes = null;
	private List<Statement> _initScript;
	private ComputeService _compute;
	private List<Integer> _nodeids;
	private List<String> _daemons;
	
	/**
	 * @param compute
	 *            ComputeService from JClouds
	 * @param instanceType
	 *            Supported instanceType (e.g. m1.medium on aws-ec2)
	 * @param image
	 *            Image to deploy
	 * @param region
	 *            Region to deploy into (image must be in this region)
	 * @param clustername
	 *            Name of cluster to deploy
	 * @param nodeids
	 *            Set of nodeids being launched
	 * @param daemons
	 *            Set of daemons to launch on this set of nodes
	 * @param zkMyId
	 *            If contain(daemons, zk) then write this zkMyId on init
	 */
	public LaunchNodeThread(ComputeService compute, Configuration config, String instanceType, String clustername, List<Integer> nodeids, List<String> daemons, Integer zkMyId) {
		_config = config;
		_region = config.getDeploymentLocation();
		_placementgroup = config.getPlacementGroup();
		_username = config.getImageUsername();
		_image = config.getDeploymentImage();
		_instanceType = instanceType;
		_clustername = clustername;
		_daemons = daemons;
		_compute = compute;
		_nodeids = nodeids;
		
		// Create initScript
		_initScript = new ArrayList<Statement>();
		_initScript.add(exec("echo \"" + daemons.toString() + "\" > /home/"+_username+"/daemons"));
		_initScript.add(exec("echo \"" + instanceType.toString() + "\" > home/"+_username+"/.instance-type"));
		if (zkMyId != null)
			_initScript.addAll(Zookeeper.writeZKMyIds(_username, zkMyId));

		// Run thread now
		this.start();
	}

	@SuppressWarnings("unchecked")
	@Override
	public void run() {
		try {
			TemplateOptions to = new TemplateOptions()
					.runAsRoot(false)
					.wrapInInitScript(true)
					.overrideLoginUser(_username)
					.inboundPorts(Tools.getPortsToOpen())
					.userMetadata("daemons", _daemons.toString())
					.runScript(new StatementList(_initScript))
					.overrideLoginCredentials(Tools.getPrivateKeyCredentials(_config))
					.authorizePublicKey(Tools.getPublicKey(_config));		
			Template template = _compute.templateBuilder()
					.hardwareId(_instanceType)
					.locationId(_region)
					.imageId(_image)
					.options(to).build();
			if (template.getOptions() instanceof AWSEC2TemplateOptions) {
				AWSEC2TemplateOptions opt = (AWSEC2TemplateOptions) template.getOptions();
				if (_placementgroup != null){
					opt.placementGroup(_placementgroup);
				}
					opt.securityGroups("unrestricted");
					if (_config.isMountLocalStorage())
						opt.mapEphemeralDeviceToDeviceName("/dev/sdb", "ephemeral0");
			}
			_newNodes = (Set<NodeMetadata>) _compute.createNodesInGroup(
					_clustername,
					_nodeids.size(),template
					);
			if (_config.getEBSStorageSize() > 0) {
				for (NodeMetadata node: _newNodes) {

					// Get AWS EC2 API
					EC2Api ec2Api = _compute.getContext().unwrapApi(EC2Api.class);
					
					// Create a volume
					Volume volume = ec2Api.getElasticBlockStoreApi().get()
							.createVolumeInAvailabilityZone(node.getLocation().getId(), _config.getEBSStorageSize());

					Status volStat = volume.getStatus();
					
					int waitTime = 0;
					//while (volStat==Status.CREATING) {
						System.out.println("Waiting for EBS");
						try {
							Thread.sleep(5000);
						} catch (Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						waitTime++;
						volStat = volume.getStatus();
					//}
					
					String provId = node.getProviderId();

					// Attach to instance
					@SuppressWarnings("unused")
					Attachment attachment = ec2Api.getElasticBlockStoreApi().get()
							.attachVolumeInRegion(_region, volume.getId(), provId, "/dev/sdx");
					System.out.println("Attached EBS");
				}
			}
			
		} catch (NoSuchElementException ex) {
			// happens often when hardwareId is not found. List all possible hardware types
			if (ex.getMessage().toLowerCase().contains("hardwareid") && ex.getMessage().toLowerCase().contains("not found")) {
				log.error("You have specified unknown hardware profile. Here follows a list of supported profiles: ");
				Set<? extends Hardware> availableHardware = _compute.listHardwareProfiles();
				for (Hardware h : availableHardware) {
					log.info(h.toString());
				}
			} else {
				log.error("Problem: ", ex);	
			}
		} catch (Exception ex) {
			log.error("Problem launching instance", ex);
		}
	}

	public List<Integer> getNodeIds() {
		return _nodeids;
	}

	public Set<NodeMetadata> getNewNodes() {
		return _newNodes;
	}
}