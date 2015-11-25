package dk.kaspergsm.stormdeploy.configurations;

import static org.jclouds.scriptbuilder.domain.Statements.exec;
import java.util.ArrayList;
import java.util.List;
import org.jclouds.scriptbuilder.domain.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.kaspergsm.stormdeploy.Tools;
import dk.kaspergsm.stormdeploy.configurations.SystemTools.PACKAGE_MANAGER;

/**
 * Contains all methods to install Ganglia
 * 
 * @author Kasper Grud Skat Madsen
 */
public class Ganglia {

	private static Logger log = LoggerFactory.getLogger(Ganglia.class);
	
	/**
	 * Install Ganglia
	 */
	public static List<Statement> install(PACKAGE_MANAGER pm, String username) {
		ArrayList<Statement> st = new ArrayList<Statement>();
		if (pm == PACKAGE_MANAGER.APT) {
			// Install monitoring base
			st.add(exec("apt-get install -y ganglia-monitor gmetad rrdtool librrds-perl librrd-dev"));

			// Install webinterface only on one node
			st.add(Tools.execOnUI("apt-get install -q -y ganglia-webfrontend",username));

			// Ensure daemons have not been started
			st.add(exec("/etc/init.d/ganglia-monitor stop"));
			st.add(exec("/etc/init.d/gmetad stop"));
		} else if (pm == PACKAGE_MANAGER.YUM) {
			// Install monitoring base
			st.add(exec("yum -y install ganglia ganglia-gmond ganglia-gmetad "));

			// Install webinterface only on one node
			st.add(Tools.execOnUI("yum -y install ganglia-web", username));

			// Ensure daemons have not been started
			st.add(exec("/etc/init.d/gmond stop"));
			st.add(exec("/etc/init.d/gmetad stop"));
			
		}	else {
				log.error("PACKAGE MANAGER not supported: " + pm.toString());
			}
		return st;
	}

	/**
	 * Configure Ganglia
	 */
	public static List<Statement> configure(String clustername, String uiHostname, PACKAGE_MANAGER pm, String username) {
		ArrayList<Statement> st = new ArrayList<Statement>();
		
		// Strip top of configurationfile
		st.add(exec("sed \'1,/Each metrics module that is referenced/d\' /etc/ganglia/gmond.conf > /etc/ganglia/stripped_gmond.conf"));
		
		// Write configuration
		st.add(exec("echo \"" + getConfiguration(clustername, uiHostname) + "\" > /etc/ganglia/gmond.conf"));
		
		// Append stripped_gmond.conf
		st.add(exec("cat /etc/ganglia/stripped_gmond.conf >> /etc/ganglia/gmond.conf"));
		
		// In case node is containing UI, it should be deaf = no!
		st.add(Tools.execOnUI("sed \"s/deaf = yes/deaf = no/\" -i \"/etc/ganglia/gmond.conf\"",username));
		
		// In case node is containing UI, it should add cluster as datasource
		st.add(Tools.execOnUI("echo data_source \"" + clustername + "\" localhost >> /etc/ganglia/gmetad.conf",username));
		if (pm == PACKAGE_MANAGER.APT) {
		// In case node is containing UI, it should create /ganglia alias for apache2 server
		st.add(Tools.execOnUI("cp /etc/ganglia-webfrontend/apache.conf /etc/apache2/sites-enabled/",username));

		// In case node is containing UI, it should modify auto_system to disabled. This allows events to be added externally to Ganglia
		st.add(Tools.execOnUI("sed \"s/$conf\\['auth_system'\\] = 'readonly'/$conf\\['auth_system'\\] = 'disabled'/\" -i \"/usr/share/ganglia-webfrontend/conf_default.php\"",username));
		
		// In case node is containing UI, it should make events.json writable by apache webserver
		st.add(Tools.execOnUI("chmod 777 /var/lib/ganglia-web/conf/events.json",username));
		}
		if (pm == PACKAGE_MANAGER.YUM) {			
				st.add(Tools.execOnUI("echo  \"Alias /ganglia /usr/share/ganglia\" > /etc/httpd/conf.d/ganglia.conf",username));
				st.add(Tools.execOnUI("echo  \"<Location /ganglia>\"  >> /etc/httpd/conf.d/ganglia.conf",username));
				st.add(Tools.execOnUI("echo  \"Require all granted\" >> /etc/httpd/conf.d/ganglia.conf",username));
				st.add(Tools.execOnUI("echo  \"</Location>\" >> /etc/httpd/conf.d/ganglia.conf",username));
		}
		return st;
	}
	
	/**
	 * Start daemons
	 */
	public static List<Statement> start(PACKAGE_MANAGER pm, String username) {
		ArrayList<Statement> st = new ArrayList<Statement>();
		if (pm == PACKAGE_MANAGER.APT) {
		// In case node is containing UI, it should enable module_rewrite for apache2
		st.add(Tools.execOnUI("a2enmod rewrite",username));
		
		// In case node is containing UI, it should restart apache2 webserver
		st.add(Tools.execOnUI("/etc/init.d/apache2 restart",username));
		
		st.add(exec("/etc/init.d/ganglia-monitor restart"));
		st.add(exec("/etc/init.d/gmetad restart"));
		} else if (pm == PACKAGE_MANAGER.YUM) {
			// In case node is containing UI, it should restart apache2 webserver
			st.add(Tools.execOnUI("/etc/init.d/httpd restart",username));

			st.add(exec("/etc/init.d/gmond restart"));
			st.add(exec("/etc/init.d/gmetad restart"));
			
		} else {
			log.error("PACKAGE MANAGER not supported: " + pm.toString());
		}
		return st;
	}
	
	private static String getConfiguration(String clustername, String uiHostname) {
		return 
		"globals {" + "\n" +
		"  daemonize = yes" + "\n" +
		"  setuid = yes" + "\n" +
		"  user = ganglia" + "\n" +
		"  debug_level = 0" + "\n" +
		"  max_udp_msg_len = 1472" + "\n" +
		"  mute = no" + "\n" +
		"  deaf = yes" + "\n" +
		"  allow_extra_data = yes" + "\n" +
		"  host_dmax = 86400 /* Remove host from UI after it hasn't report for a day */" + "\n" +
		"  cleanup_threshold = 300 /*secs */" + "\n" +
		"  gexec = no" + "\n" +
		"  send_metadata_interval = 30 /*secs */" + "\n" +
		"}" + "\n" +
		"" + "\n" +
		"cluster {" + "\n" +
		"  name = \\\"" + clustername + "\\\"" + "\n" +
		"  owner = \\\"unspecified\\\"" + "\n" +
		"  latlong = \\\"unspecified\\\"" + "\n" +
		"  url = \\\"unspecified\\\"" + "\n" +
		"}" + "\n" +
		"" + "\n" +
		"host {" + "\n" +
		"  location = \\\"unspecified\\\"" + "\n" +
		"}" + "\n" +
		"" + "\n" +
		"udp_send_channel {" + "\n" +
		"  host = " + uiHostname + "\n" +
		"  port = 8649" + "\n" +
		"  ttl = 1" + "\n" +
		"}" + "\n" +
		"" + "\n" +
		"udp_recv_channel {" + "\n" + 
		"  port = 8649" + "\n" +
		"}" + "\n" +
		"" + "\n" +
		"tcp_accept_channel {" + "\n" +
		"  port = 8649" + "\n" +
		"}" + "\n" +
		"/* Each metrics module that is referenced by gmond must be specified and\n"; // Removed when stripping with sed
	}
}
