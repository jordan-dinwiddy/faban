/* The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.sun.com/cddl/cddl.html or
 * install_dir/legal/LICENSE
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at install_dir/legal/LICENSE.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Copyright 2005 Sun Microsystems Inc. All Rights Reserved
 */
package com.sun.services;

import com.sun.faban.common.Command;
import com.sun.faban.common.CommandHandle;
import com.sun.faban.harness.RunContext;
import com.sun.faban.harness.services.ServiceContext;
import com.sun.faban.harness.Context;

import com.sun.faban.harness.services.Configure;
import com.sun.faban.harness.services.Startup;
import com.sun.faban.harness.services.Shutdown;
import java.io.File;

import java.util.ArrayList;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * This class implements the service of configure/start/stop Oracle instances.
 * it is used by the benchmark to start the OracleAgent on the server machine 
 * and perform these operations throught OracleAgent.
 *
 * @author Sheetal Patil based on work done by Ramesh Ramachandran
 */

public class OracleService {
    
    @Context public ServiceContext ctx;
    Logger logger = Logger.getLogger(OracleService.class.getName());
    String oracleHome,  myServers[], oracleSid;
    ArrayList<String> listners = new ArrayList<String>();
    String oracleStartCmd, oracleStopCmd, startupConf, oracleBin;
    String[] env;
    CommandHandle serverHandles[];
    CommandHandle listnerHandles[];

    @Configure public void configure() {
        logger.info("Configuring oracle service ");
        myServers = ctx.getUniqueHosts();
        oracleHome = ctx.getProperty("serverHome");
        oracleSid = ctx.getProperty("serverId");
        startupConf = ctx.getProperty("startupConf"); // What is this used for?
        oracleBin = oracleHome + "bin";
        if (!oracleHome.endsWith(File.separator))
            oracleHome = oracleHome + File.separator;
        if (!oracleBin.endsWith(File.separator))
            oracleBin = oracleBin + File.separator;
        String includeListners = ctx.getProperty("includes");
        StringTokenizer st = new StringTokenizer(includeListners,"; ,\n");
        while(st.hasMoreTokens()){
            listners.add(st.nextToken());
        }
        oracleStartCmd = oracleBin + "sqlplus /nolog <<EOT\nconnect " +
                " / as sysdba \nstartup pfile=" + startupConf + "\nexit\nEOT";
        oracleStopCmd = oracleBin + "sqlplus /nolog <<EOT\nconnect " +
                " / as sysdba\nshutdown" + "\nexit\nEOT";
        serverHandles = new CommandHandle[myServers.length];
        logger.info("OracleService Configure complete.");
        String[] setEnv = {"ORACLE_SID="+oracleSid, "ORACLE_HOME=" + oracleHome,
                    "PATH=" + oracleBin, "LD_LIBRARY_PATH=" + oracleHome +"lib",
           "LD_LIBRARY_PATH_64="+oracleHome+"lib" + File.separator + "sparcv9"};
        this.env = setEnv;

    }

    @Startup public void startup() {
        for (int i = 0; i < myServers.length; i++) {
            logger.info("Starting oracle on " + myServers[i]);
            Command startCmd = new Command(oracleStartCmd);
            startCmd.setEnvironment(env);
            logger.fine("Starting oracle with: " + oracleStartCmd);
            startCmd.setSynchronous(false); // to run in bg
            try {
                // Run the command in the background
                if ( !checkServerStarted(i)) {
                    serverHandles[i] = RunContext.exec(myServers[i], startCmd);
                    logger.info("Completed Oracle server startup successfully on" + myServers[i]);
                    //CommandHandle pid = RunContext.exec(myServers[i], new Command("pgrep ora_pmon"));
                    //FileHelper.writeStringToFile(pid.fetchOutput(0).toString(), new File(oracleHome+myServers[i]+".pid"));
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to start Oracle server.", e);
            }
            if(ctx.getProperty("includes").equalsIgnoreCase("true")) {
                logger.info("Starting listner");
                Command listerCmd = new Command(oracleBin + "lsnrctl start");
                listerCmd.setSynchronous(false); // to run in bg
                try {
                    // Run the command in the background
                    if (!checkListnerStarted(myServers[i])) {
                        RunContext.exec(myServers[i], listerCmd);
                        logger.info("Completed listner startup successfully on" + myServers[i]);
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to start listner.", e);
                }

            }
        }       
    }

    @Shutdown public void shutdown() throws Exception {
        for (int i = 0; i < myServers.length; i++) {
            String myServer = myServers[i];
            if (checkServerStarted(i)) {
                if (checkListnerStarted(myServer)) {
                    stopListner(myServer);
                }
                stopServer(myServer);
            }
        }
    }

    private boolean checkServerStarted(int i) throws Exception {
        boolean started = false;
        if (serverHandles[i] != null) {
            started = true;
        }
        return started;
    }

    private boolean checkListnerStarted(String hostName) throws Exception {
        boolean started = false;
        CommandHandle ch = RunContext.exec(hostName, new Command(oracleBin + "lsnrctl status"));
        if (ch.fetchOutput(0) != null){
            started = true;
        }
        return started;
    }

    private void stopServer(String serverId){
        logger.info("Stopping Oracle server on" + serverId);
        try {
            // First kill oracle
            Command stopCmd = new Command(oracleStopCmd);
            RunContext.exec(serverId, stopCmd);
            logger.info("Oracle server stopped successfully on" + serverId);
        } catch (Exception ie) {
            logger.warning("Kill Oracle failed with " + ie.toString());
            logger.log(Level.FINE, "kill Oracle Exception", ie);
        }
    }

    private void stopListner(String serverId){
        logger.info("Stopping listner on" + serverId);
        try {
            Command stopCmd = new Command(oracleBin + "lsnrctl stop");
            RunContext.exec(serverId, stopCmd);
            logger.info("Listner stopped successfully on" + serverId);
        } catch (Exception ie) {
            logger.warning("Kill listner failed with " + ie.toString());
            logger.log(Level.FINE, "kill listner Exception", ie);
        }
    }

    
}
