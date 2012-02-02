/*
 * Copyright (c) 2011, Thomas Riedmaier, TU M�nchen
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Crossbear nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THOMAS RIEDMAIER BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

/**
 * This class provides all functionality required to download and process HuntingTaskLists. "Processing" in this context means "Look at each Task and decide whether it should be executed or not".
 * 
 * @param cbFrontend The cbFrontend-class that will be used to display information/errors to the user and to read the user preferences and settings.
 * 
 * @author Thomas Riedmaier
 */
function CBHTLProcessor(cbFrontend) {
	this.cbFrontend = cbFrontend;
	
	// The list of HuntingTasks that was received from the Crossbear server in its CBMessageHuntingTask[]-representation.
	this.taskList = [];
	
	// The list of the IDs of the HuntingTasks within the taskList[]
	this.taskIDList = [];
	
	// The current IPs of the Crossbear server
	this.serverIPv6;
	this.serverIPv4;
	
	// The system's current public IPs
	this.publicIPv6;
	this.publicIPv4;
	
	// "this" does not always point to THIS object (especially in callback functions). Therefore I use the "self" variable to hold a handle on THIS object
	var self = this;

	// Initialize the member function references for the class prototype (like this it's only done once and not every time a instance of this object is created)
	if (typeof (_cbhtlprocessor_prototype_called) == 'undefined') {
		_ccbhtlprocessor_prototype_called = true;
		
		/**
		 * Request the current HuntingTaskList from the Crossbear server using parseHuntingTaskList as callback-function (i.e. parseHuntingTaskList will parse the HuntingTaskList after it is received)
		 */
		CBHTLProcessor.prototype.requestHuntingTaskList = function requestHuntingTaskList() {
			cbFrontend.displayInformation("Pulling Hunting Tasks from CrossbearServer");
			cbFrontend.cbnet.retrieveBinaryFromUrl("https://" + cbFrontend.cbServerName + "/getHuntingTaskList.jsp", cbFrontend.cbServerName + ":443", self.parseHuntingTaskList,null);
		};
		

		/**
		 * Parse a requested HuntingTaskList. That is try to decode it as CBMessage[] using the messageBuilder-function. The CBMessage[] should contain a PublicIPNotification-message, a CurrentServerTime-message and a variable number of
		 * HuntingTask-messages. Finally request the IPs of the Crossbear server. These will be required in order to get all of the systems PublicIPs. These will in turn be needed in order to decide whether or not to (re-)execute a Task.
		 * 
		 * Please note: This function is a XMLHTTPRequest-callback-function
		 */
		CBHTLProcessor.prototype.parseHuntingTaskList = function parseHuntingTaskList() {
			
			// Check if the server's reply has entirely been received
			if ((this.readyState == 4) && (this.status == 200)) {
				
				// If yes: check if that reply actually contained data
				var output = this.response;
				if (output) {

					// If yes: Reset the global variables that were set during the last parsing
					self.taskList = [];
					self.taskIDList = [];
					
					self.serverIPv6 = '';
					self.serverIPv4 = '';
					
					self.publicIPv6 = '';
					self.publicIPv4 = '';
					
					// Try to Decode the server's reply as an array of CBMessages
					var serverMessages = messageBuilder(new Uint8Array(output),cbFrontend);
					
					// Read the messages and store their content at the appropriate places
					for(var i = 0;i<serverMessages.length;i++){
						
						// Hunting tasks will be stored locally (will be checked later and depending on that forwarded to the CBHunter(WorkerThread) )
						if(serverMessages[i].constructor.name  == "CBMessageHuntingTask"){
							self.taskList.push(serverMessages[i]);
							self.taskIDList.push(serverMessages[i].getTaskID());
						
						// The current server time will be stored in the cbFrontend
						} else if(serverMessages[i].constructor.name  == "CBMessageCurrentServerTime"){
							cbFrontend.calcAndStoreCbServerTimeDiff(serverMessages[i].getCurrentServerTime());
						
						// The PublicIP will be stored locally and in the CBHunter(WorkerThread)
						} else if (serverMessages[i].constructor.name  == "CBMessagePublicIPNotif"){
							if(serverMessages[i].getIPVersion() ==4){
								self.publicIPv4 = serverMessages[i].getPublicIP();
							} else {
								self.publicIPv6 = serverMessages[i].getPublicIP();
							}
							cbFrontend.cbhunter.addPublicIP(serverMessages[i]);
							
						} else{
							cbFrontend.displayTechnicalFailure("CBHTLProcessor:parseHuntingTaskList: received unknown message from server.", true);
						}

					}

					cbFrontend.displayInformation("Received " + self.taskList.length + " tasks from Crossbear Server.");
					
					// If there were HuntingTasks in the HTL request the IPs of the Crossbear server. These will be required in order to get all of the systems PublicIPs. These will in turn be needed in order to decide whether or not to (re-)execute a Task.
					if (self.taskList.length > 0) {
						cbFrontend.cbhunter.requestCBServerIPs(self.storeCurrentServerIps);
						return;
					}

				} else {
					cbFrontend.displayTechnicalFailure("CBHTLProcessor:parseHuntingTaskList: received empty reply from cbServer when asking for Hunting Task List!", true);
				}
			} else if ((this.readyState == 4) && (this.status == 0)) { 
				cbFrontend.displayTechnicalFailure("CBHTLProcessor:parseHuntingTaskList: could not connect to cbServer (connection timed out)!", false);
			} else if ((this.readyState == 4)) {
				cbFrontend.displayTechnicalFailure("CBHTLProcessor:parseHuntingTaskList: could not connect to cbServer (HTTP-STATUS: "+this.status+":"+this.statusText+")!", true);
			}

		};	

		/**
		 * Receive the current IPs of the Crossbear Server and store them. A known serverIP of a specific IP-version means that the system supports that IP-version. Therefore a PublicIP of that IP-version will be requested for that version
		 * (if not already known). It will be stored using either the "storePublicIPv4"-function or the "storePublicIPv6"-function.
		 */
		CBHTLProcessor.prototype.storeCurrentServerIps = function storeCurrentServerIps(serverIPv4, serverIPv6) {
			
			// Store the IPs of the Crossbear server
			self.serverIPv4 = serverIPv4;
			self.serverIPv6 = serverIPv6;
			
			// Does the system support ipv4?
			if(self.serverIPv4 != ""){
				
				// If yes: Check if a ipv4 PublicIp is already known (i.e. was it included in the server's reply?)
				if(self.publicIPv4 == ""){
					// If not: Request it
					cbFrontend.cbhunter.requestPublicIP(self.serverIPv4,4,self.storePublicIPv4);
					
				} else {
					// If it was: store it (will have no effect since it is already stored but calling that function needs to be done in order to go on with the parsing)
					self.storePublicIPv4(self.publicIPv4);
				}
				return;
				
			// Does the system support ipv6?
			} else if (self.serverIPv6 != ""){
				
				// If yes: Check if a ipv6 PublicIp is already known (i.e. was it included in the server's reply?)
				if(self.publicIPv6 == ""){
					// If not: Request it
					cbFrontend.cbhunter.requestPublicIP(self.serverIPv6,6,self.storePublicIPv6);
					
				} else {
					// If it was: store it (will have no effect since it is already stored but calling that function needs to be done in order to go on with the parsing)
					self.storePublicIPv6(self.publicIPv6);
				}
				return;
			}
			
			return;
		};
		
		/**
		 * Store the system's current PublicIPv4 and check if the system supports IPv6. If it does: Request the system's current PublicIPv6 (if not already known). If not: go on with the parsing of the HTL. That is "Get the time during which the HTL's Tasks were executed from the current system's publicIP for the last time."
		 */
		CBHTLProcessor.prototype.storePublicIPv4 = function storePublicIPv4(publicIPv4) {
			
			// Store the PublicIP
			self.publicIPv4 = publicIPv4;

			// Does the system support ipv4 AND ipv6?
			if (self.serverIPv6 != "") {
							
				// If yes: Check if a ipv6 PublicIp already known (i.e. was it included in the server's reply?)
				if(self.publicIPv6 == ""){
					// If not: Request it
					cbFrontend.cbhunter.requestPublicIP(self.serverIPv6,6,self.storePublicIPv6);
					
				} else {
					// If it was: store it (will have no effect since it is already stored but calling that function needs to be done in order to go on with the parsing)
					self.storePublicIPv6(self.publicIPv6);
				}
				
				return;
			
			// If the system is IPv4-only then go on with the parsing of the HTL. That is "Get the time during which the HTL's Tasks were executed from the current system's publicIP for the last time."
			} else {
				self.getLastExecutionTimesForTasks();
				return;
			}
		};

		/**
		 * Store the system's current PublicIPv6 and go on with the parsing of the HTL. That is "Get the time during which the HTL's Tasks were executed from the current system's publicIP for the last time."
		 */
		CBHTLProcessor.prototype.storePublicIPv6 = function storePublicIPv6(publicIPv6) {
			
			// Store the PublicIP
			self.publicIPv6 = publicIPv6;

			// Go on with the execution
			self.getLastExecutionTimesForTasks();
			return;

		};
		
		/**
		 * Request the time during which the HTL's Tasks were executed from the current system's publicIP for the last time. This information is stored in the performedTasks-table
		 */
		CBHTLProcessor.prototype.getLastExecutionTimesForTasks = function getLastExecutionTimesForTasks() {

			// Build a SQL-Query that will look for the maximal LastExecutionTime-value of a Task whose ID is in the taskIDList.
			var params = new Object();
			var sql_stmt = "SELECT TaskID, MAX(ServerTimeOfExecution) AS LastExecutionTime FROM performedTasks WHERE TaskID IN ( ";
			for ( var i = 0; i < self.taskIDList.length; i++) {
				if (i != 0) {
					sql_stmt += ",";
				}

				sql_stmt += " :tid" + i + " ";
				params["tid" + i] = self.taskIDList[i];
			}

			// Single Constraint: The execution must have been from the current publicIP
			sql_stmt += " ) AND ( PublicIP = :pipv4 OR PublicIP = :pipv6 ) GROUP BY TaskID ";
			params["pipv4"] = self.publicIPv4;
			params["pipv6"] = self.publicIPv6;

			// Execute the query and send its results to the getAndApplyTaskPolicys-function
			cbFrontend.cbdatabase.executeAsynchronous(sql_stmt, params, self.getAndApplyTaskPolicys);
		};

		// Currently there are only two possible policies for a task: Execute it ("OK") or not ("SKIP")
		var CBTaskExecutionPolicies = {
				OK : 0,
				SKIP : 1,
			};

		/**
		 * Get a execution policy (one of the CBTaskExecutionPolicies) for each Task and execute it if the policy is "OK".
		 * 
		 * @param lastExecutionTimesOfTasks An array of mozIStorageRows generated by a SQL-Query requesting the time for all tasks ( whose IDs are in the taskIDList[] ) during which they were executed from the current system's publicIP for the last time.
		 */
		CBHTLProcessor.prototype.getAndApplyTaskPolicys = function getAndApplyTaskPolicys(lastExecutionTimesOfTasks) {

			// Count the number of accepted Tasks
			var tasksAccepted = 0;

			// Get a policy for each Task of the HTL
			while (self.taskList.length > 0) {

				// Parse list in random order -> Get a random entry of the HTL
				var candidateTaskIndex = parseInt(Math.random() * self.taskList.length);
				var candidateTask = self.taskList[candidateTaskIndex];
				self.taskList.splice(candidateTaskIndex, 1); // Remove from list

				// Get last execution time of the task (will be "" if never executed from the current PublicIP)
				var lastExecutionTime = "";
				for ( var i = 0; i < lastExecutionTimesOfTasks.length; i++) {
					if (lastExecutionTimesOfTasks[i].getResultByName("TaskID") == candidateTask.getTaskID()) {
						lastExecutionTime = lastExecutionTimesOfTasks[i].getResultByName("LastExecutionTime");
						break;
					}
				}

				// Get the execution policy based on the task's last execution time and the current system's public IP
				var taskPolicy = self.getPolicyForTask((candidateTask.getIPVersion() == 4) ? self.publicIPv4 : self.publicIPv6, lastExecutionTime);

				// If the policy said "SKIP": don't execute the task
				if (taskPolicy == CBTaskExecutionPolicies.SKIP) {
					cbFrontend.displayInformation("Skipping execution of task " + candidateTask.getTaskID());

				// If it said "OK" add the task to the CBHunter(WorkerThread) so it will be executed and increment the number of accepted tasks.
				} else if (taskPolicy == CBTaskExecutionPolicies.OK) {
					tasksAccepted++;
					cbFrontend.cbhunter.addTask(candidateTask);

				} else {
					cbFrontend.displayTechnicalFailure("CBHTLProcessor:getAndApplyTaskPolicys: unknown policy (" + taskPolicy + ") received!", true);

				}

			}

			cbFrontend.displayInformation("Parsing of Hunting Task List done. " + tasksAccepted + " tasks have been accepted.");
			return;

		};
		
		/**
		 * Decide whether a Task should be executed or not (i.e. get its execution policy). Currently this is equal to checking if the Task CAN be executed (i.e. does the system support the ip-version of the task) and is it's last execution for the
		 * current PublicIP long enough in the past?
		 * 
		 * @param publicIP The current system's public IP of the same version that the IP of target of the HuntingTask is ("" if there is no PublicIP for that version)
		 * @param lastExecutionTime The last time the Task has been executed from the current system's public IP ("" if never)
		 * @returns One of the CBTaskExecutionPolicies
		 */
		CBHTLProcessor.prototype.getPolicyForTask = function getPolicyForTask(publicIP, lastExecutionTime) {


			// Scanning a target with a unavailable protocol version is simply not possible -> SKIP
			if (publicIP == "") {
				return CBTaskExecutionPolicies.SKIP;
			}
			
			// Don't reexecute (execute with same public ip) tasks too often. Therefore get the current server time and check if the time of the last execution for the current PublicIP of the task is far enough in the past.
			var currentTime = cbFrontend.getServerTime();
			if(lastExecutionTime != "" && (lastExecutionTime + cbFrontend.getUserPref("hunter.taskReexecutionInterval", "int")>currentTime)){
				
				// If that is the case -> SKIP ...
				return CBTaskExecutionPolicies.SKIP;
			}

			// ... if not -> Execute
			return CBTaskExecutionPolicies.OK;
		};
	}
}