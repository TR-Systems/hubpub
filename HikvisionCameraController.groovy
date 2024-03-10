//******************************************************************************
//* Hikvision Camera Controller - Device Driver for Hubitat Elevation
//******************************************************************************
// Copyright 2024 Thomas R Schmidt, Wildwood IL
//******************************************************************************
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//******************************************************************************
// This driver allows you to trigger Alarm Input events on the camera and
// enable/disable Motion Detection features by running its custom commands
// from your rules and apps. It also acts as a Motion Sensor in HE if your
// camera has the Alarm Server/HTTP Listening feature available.
//
// The User Guide is required reading.
// https://tr-systems.github.io/web/HCC_UserGuide.html
// Contact for support: trsystems.help at the little G mail place.
//******************************************************************************
// Change Log
// Date      Version  Release Notes
// 24-01-26  1.0.0    First Release: Please refer to the User Guide
// 24-02-05  1.0.1    Remove/Replace Ping function from Ok2Run method for all Commands
//                    * Instead, set zDriver OFF if GET request for current state times out
//                    * Test/Practice versioning and update with HPM
// 24-02-06  1.0.2    Add link to User Guide on device driver page (provided by jtp10181)
// 24-02-07  1.0.3    Remove Link to User Guide from top of device driver page due to
//                    overlay of Events & Logs buttons when viewing device on a phone.
// 24-02-22  1.0.4    Bug fix: Update old Hikvision IPMD url paths to ISAPI paths.
//                    * Affected features: Basic Motion, Alarm Out trigger, IO Status.
//                    * Required to support newer cameras. May break older cams.
// 24-02-23  1.0.5    Bug fix: Null value exception for camera name when logging GET error during save
//                    Alarm Server: Log event messages for "Unknown" events for reporting to tr-systems.
// 24-02-26  1.0.6    Alarm Server: Add support for eventType "duration" and associated relationEvent
// 24-03-07  1.1.0    Add Pushable Button for Motion Events
// 24-03-11  2.0.0    Your Choice of Driver: Alarm Server, Controller or Both
//                    + Minimize Camera Validation requirements in Saving Preferences
//                    + Change Motion and Alarm I/O Feature Attributes to State Variables
//                    + Tested upgrade from 1.0.5 and 1.1.0 without saving preferences on existing devices
//******************************************************************************
import groovy.transform.Field // Needed to use @Field static lists/maps
//******************************************************************************
@Field static final String DRIVER = "HCC 2.0.0"
@Field static final String USER_GUIDE = "https://tr-systems.github.io/web/HCC_UserGuide.html"
//******************************************************************************
metadata {
    definition (name: "Hikvision Camera Controller", 
                author: "Thomas R Schmidt", namespace: "tr-systems", // github userid
                singleThreaded: true) // 
    {
        capability "Actuator"
        capability "Switch"
        capability "MotionSensor"
        capability "PushableButton"  //** v110

        command "on" , [[name:"Trigger Alarm"]]
        command "off" , [[name:"Clear Alarm"]]
        command "Enable", [[name:"Features",type:"STRING",description:"Features: m.p.in.lc.rx.re.or.ub.ai"]]
        command "Disable", [[name:"Features",type:"STRING",description:"Features: m.p.in.lc.rx.re.or.ub.ai"]]
        command "push", [[name: "Event", type: "NUMBER", description: "1=in 2=lc 3=m 4=p 5=or 6=re 7=rx 8=ub"]] //** v110
        attribute "motion", "STRING"     // active/inactive
        attribute "numberOfButtons", "NUMBER"  // last button pushed ** v110
        attribute "pushed", "NUMBER"           // last button pushed ** v110
        attribute "switch", "STRING"     // alarm on/off follows AlarmIn state ** v110
        attribute "zDriver", "STRING"    // State of this device in HE: OK, ERR, OFF, CRED
        // OK = Everything is groovy
        // ERR = Unexpected get/put errors occurred.
        // OFF = Camera is offline
        // CRED = Authentication failed, credentials on the camera have changed
        // FAILED = Only during camera validation when saving preferences
	}
    preferences 
    {
        input(name: "devUse", type: "enum", //** v200
              title:"Select Driver Components",
              description: " ",
              options: ["Alarm Server","Controller","Both"],
              defaultValue: "Both",
              required: true)
        input(name: "devIP", type: "string", 
              description: " ",
              title:"Camera or NVR IP Address",
              required: true)
        input(name: "devPort", type: "string",
              title:"Camera or NVR Virtual Port",
              description: "(for controller)",
              defaultValue: "80",
              required: false) //** v200
        input(name: "devCred", type: "password",
              title:"Credentials for Login",
              description: "userid:password (for controller)",
              required: false) //** v200
        input(name: "devMotionReset", type: "number",
              title:"Reset Interval for Motion Detection",
              description: "(From 1 to 20 minutes)",
              range: "1..20",
              devaultValue: 1,
              required: false) //** v200
        input(name: "devResetCounters", type: "enum",
              title:"Reset Alarm Server Counters",
              options: ["Daily","Weekly","Monthly","Only on Save"],
              defaultValue: "Only on Save",
              required: false) //** v200
        input(name: "devExclude", type: "string",
              title:"Exclude from Controller",
              description: "List: m.p.in.lc.rx.re.or.ub.ai.ao",
              required: false)
        input(name: "devExcludeA", type: "string", //** v200
              title:"Exclude from Alarm Server",
              description: "List: m.p.in.lc.rx.re.or.ub",
              required: false)
        input(name: "devName", type: "string",  //** v200
              title:"Optional Name for Logging",
              description: " ",
              required: false)
        input(name: "debug", type: "bool",
              title: "Debug logging for Controller",
              description: "(resets in 30 minutes)",
              defaultValue: false)
        input(name: "debuga", type: "bool",
              title: "Debug logging for Alarm Server",
              description: "(resets in 30 minutes)",
              defaultValue: false)
      	// Link to User Guide
    	input name: "UserGuide", type: "hidden", title: fmtHelpInfo("User Guide")
    }
}
//******************************************************************************
// This "global" is used to pass status (OK or error msg) back from the
// SendGet/Put Request methods, then used for logging and program control in the
// calling methods. Don't mess with strMsg unless you know what you're doing.
String strMsg = " " 
Boolean SavingPreferences = false
//******************************************************************************
// CODER BEWARE HACK provided by jtp10181 - unsupported - undocumented
//******************************************************************************
String fmtHelpInfo(String str) {
	String prefLink = "<a href='${USER_GUIDE}' target='_blank'>${str}<br><div style='font-size: 70%;'>${DRIVER}</div></a>"
    return "<div style='font-size: 160%; font-style: bold; padding: 2px 0px; text-align: center;'>${prefLink}</div>"
}
//******************************************************************************
// INSTALLED - INSTALLED - INSTALLED - Installing New Camera Device
//******************************************************************************
void installed() {
    log.info "Installing new camera"
    log.info "Setting device Name to Label: " + device.getLabel()
    device.setName(device.getLabel())
    sendEvent(name:"zDriver",value:"Please read the User Guide before adding your first camera (scroll down for link)")
}
//******************************************************************************
// UPDATED - UPDATED - UPDATED - Preferences Saved
//******************************************************************************
void updated() {
    String errcd = ""
    String dni = ""
    String cname = ""  //** v200
    unschedule()    
    state.clear()
    //************************************************************* v200 START
    cname = device.getLabel()
    if (devName == null || devName.trim() == "") {
        devName = cname
        device.updateSetting("devName", [value:"$cname", type:"string"])
    }

    cname = cname.toUpperCase()
    log.warn "Saving Preferences for " + cname + ", using " + devUse

    if (devUse == "Alarm Server") {
        if (devMotionReset == null) {device.updateSetting("devMotionReset", [value:1, type:"number"])}
        log.info "Using Motion Reset Interval: " + device.getSetting("devMotionReset")
        devIP = devIP.trim()
        device.updateSetting("devIP", [value:"${devIP}", type:"string"])
        if (GenerateDNI(devIP) == "ERR") {
            sendEvent(name:"zDriver",value:"FAILED")
            return
        }
        log.info "$cname Alarm Server now waiting for Event messages from " + devIP
        sendEvent(name:"motion",value:"inactive")
        sendEvent(name:"numberOfButtons",value:8)
        sendEvent(name:"zDriver",value:"OK")
        return
    }
    //************************************************************* v200 END
   
    devIP = devIP.trim()
    devPort = devPort.trim()
    devCred = devCred.trim()
    device.updateSetting("devIP", [value:"${devIP}", type:"string"])
    device.updateSetting("devPort", [value:"${devPort}", type:"string"])
    device.updateSetting("devCred", [value:"${devCred}", type:"string"])
    // Start Fresh
    device.removeDataValue("Name")   //** v200
    device.removeDataValue("Model")
    device.removeDataValue("Firmware")
    device.updateDataValue("CamID",devCred.bytes.encodeBase64().toString())

    if (devCred == null) {
        log.error "Credentials are required"
        sendEvent(name:"zDriver",value:"FAILED")
        return
    }

    long port = devPort.toInteger()
    if (port >= 65001 && devUse == "Both") {
        log.error "NVR Virtual Port is for Controller use only"
        sendEvent(name:"zDriver",value:"FAILED")
        return
    }

    if (devCred.length() > 6 && devCred.substring(0,6) == "admin:") {
        log.error "Hikvision admin account not allowed"
        sendEvent(name:"zDriver",value:"FAILED")
        return
    }

    errcd = GetCameraInfo()
    if (errcd != "OK") {
        sendEvent(name:"zDriver",value:"FAILED")
        return
    }
    // Validate the Operator account
    errcd = GetUserInfo()
    if (errcd != "OK") {
        sendEvent(name:"zDriver",value:"FAILED")
        return
    }
    //************************************************************* v200 START
    if (devUse == "Both") {
        if (GenerateDNI(devIP) == "ERR") {
            sendEvent(name:"zDriver",value:"FAILED")
            return
        }
    }
    SavingPreferences = true
    //************************************************************* v200 END
    errcd = GetSetStates()
    // Returns OK, NA w/StrMsg=new exclude filter, or ERR/CRED w/strMsg=error message
    if (errcd == "ERR" || errcd == "CRED") {
        sendEvent(name:"zDriver",value:"FAILED")
        return
    }
    SavingPreferences = false                                  //** v200
    // Add features not found to the Exclude filter
    if (errcd == "NA") {
        strMsg = strMsg + devExclude
        log.warn "Setting new Exclude Filter:" + strMsg
        device.updateSetting("devExclude", [value:"${strMsg}", type:"string"])
    }

    if (debug || debuga) {runIn(1800, ResetDebugLogging, overwrite)}
    
    log.warn "$cname validated, ready for operation"
    if (devUse == "Both") {                        //** v200
        sendEvent(name:"motion",value:"inactive")  //** v200
        sendEvent(name:"numberOfButtons",value:8)  //** v200
    }
    sendEvent(name:"switch",value:"off")           //** v110
    sendEvent(name:"zDriver",value:"OK")
}
//***************************************************************** v200 START
// GENERATE DNI - GENERATE DNI - GENERATE DNI - GENERATE DNI
//******************************************************************************
private GenerateDNI(String ip) {
    String dni = ip.tokenize(".").collect {String.format( "%02x", it.toInteger() ) }.join()
    dni = dni.toUpperCase()
    try {device.deviceNetworkId = "${dni}"
    } catch (Exception e) {
        log.error e.message
        return("ERR")
    }
    return(dni)
}
//******************************************************************* v200 END
// RESET DEBUG LOGGING - RESET DEBUG LOGGING - RESET DEBUG LOGGING
//******************************************************************************
void ResetDebugLogging() {
    log.info "Debug logging is off"
    device.updateSetting("debug", [value:false, type:"bool"])
    device.updateSetting("debuga", [value:false, type:"bool"])
}
//******************************************************************************
// GET CAMERA INFO - GET CAMERA INFO - GET CAMERA INFO - GET CAMERA INFO
//******************************************************************************
private GetCameraInfo() {
    String errcd = ""
    log.info "GET: http://" + devIP + ":" + devPort + FeaturePaths.SysInfo
    //  If the response from the GET request is successful, the xml returned
    //  will be presented in the format requested and strMsg will be "OK".
    //  Otherwise, xml will be null and strMsg will contain the error message.
    //  Further analysis of GET errors is then performed by LogGETError.
    //  This applies to all calls to the SendGet and SendPut Request methods.
    //  Don't mess with strMsg unless you know what you're doing.
    strMsg = ""
    def xml = SendGetRequest(FeaturePaths.SysInfo,"GPATH")
    if (strMsg != "OK") {
        errcd = LogGETError()
        return(errcd)
    }
    log.info "Device Type: " + xml.deviceType.text()
    log.info "Name: " + xml.deviceName.text()
    log.info "Model: " + xml.model.text()
    log.info "Firmware: " + xml.firmwareVersion.text() + " " + xml.firmwareReleasedDate.text()

    if (xml.deviceType.text() == "NVR" || xml.deviceType.text() == "DVR") {
        strMsg = "You have connected to a Hikvision NVR/DVR, Use the Virtual Host Port or POE Subnet address to access the camera"
        log.error strMsg
        return("ERR")
    }
    device.updateDataValue("Name",xml.deviceName.text())
    device.updateDataValue("Model",xml.model.text())
    device.updateDataValue("Firmware",xml.firmwareVersion.text() + " " + xml.firmwareReleasedDate.text())
    return("OK")
}
//******************************************************************************
// GET USER INFO - GET USER INFO - GET USER INFO - GET USER INFO - GET USER INFO
//******************************************************************************
private GetUserInfo() {
    String errcd = ""
    log.info "Validating Operator account"
    // This GET will only return the user being used, not the entire list
    // Only the admin account gets the entire list of users
    // So glad it does this because now its easy to get the user id for the next step
    xml = SendGetRequest(FeaturePaths.CamUsers,"GPATH")
    if (strMsg != "OK") {
        errcd = LogGETError()
        return(errcd)
    }
    String userid = xml.User.id.text()
    log.info "UserID: " + userid
    log.info "UserLevel: " + xml.User.userLevel.text()
    if (xml.User.userLevel.text() != "Operator") {
        strMsg = "User account on camera is not an Operator"
        log.warn strMsg
        return("ERR")
    }
    String path = FeaturePaths.UserPerm + userid
    // Get User Permissions
    xml = SendGetRequest(path,"GPATH")
    if (strMsg != "OK") {
        errcd = LogGETError()
        return(errcd)
    }
    if (xml.remotePermission.parameterConfig.text() != "true" || xml.remotePermission.alarmOutOrUpload.text() != "true") {
        strMsg = "Operator account on camera must have both Remote Parameters and Remote Notify options selected."
        log.error strMsg
        return("ERR")
    }
    log.info "Operator account validated"
    return("OK")
}
//******************************************************************************
// GET SET STATES - GET SET STATES - GET SET STATES - GET SET STATES
//******************************************************************************
def GetSetStates() {
    Boolean err = false
    Boolean na = false
    String newfilter = ""
    String errcd = " "
    log.info "Initializing the State of All Available Features"
    if (devExclude == null) {devExclude = ""}
    errcd = "OK"
    sendEvent(name:"motion",value:"inactive")
    for (feature in FeatureCodesToName) {
        if (!devExclude.contains("$feature.key")) {
            errcd = GetSetFeatureState("$feature.value")
            if (errcd == "ERR" || errcd == "CRED") {break} 
            if (errcd == "NA") {
                na = true
                newfilter = newfilter + feature.key + "."
            }
        } else {
//          sendEvent(name:"$feature.value", value:"NA")   //** v200
            state."$feature.value"  = "NA"                 //** v200
        }
    }
    if (errcd == "ERR" || errcd == "CRED") {return(errcd)}
    if (na) {
        errcd = "NA"
        strMsg = newfilter
    }
    return(errcd)
}
//******************************************************************************
// GET SET FEATURE STATE - GET SET FEATURE STATE - GET SET FEATURE STATE
//******************************************************************************
// Returns errcd OK,NA,ERR,CRED w/strMsg=error message
private GetSetFeatureState(String Feature) {
    String errcd = ""
    String camstate = ""

    String Path = FeaturePaths."$Feature"
    
    def xml = SendGetRequest(Path, "GPATH")
    
    if (strMsg == "OK") {
        if (Feature == "AlarmIO") {
            camstate = xml.IOPortStatus[0].ioState.text()
            log.info "AlarmIn: " + camstate
            state.AlarmIn = camstate
            if (camstate == "active") {sendEvent(name:"switch",value:"on")}  //** v110
            else {sendEvent(name:"switch",value:"off")}                      //** v110
            camstate = xml.IOPortStatus[1].ioState.text()
            log.info "AlarmOut: " + camstate
            state.AlarmOut = camstate
        } else {
            camstate = xml.enabled.text()
            if (camstate == "true") {camstate = "enabled"} else {camstate = "disabled"}
            state."${Feature}" = camstate              //** v200
            log.info Feature + ": " + camstate
        }
        return("OK")
    } else {
        errcd = LogGETError()
        if (errcd == "NA") {
            log.info Feature + " is not available"
            state."${Feature}" = "NA"  //** v200
        }
        return(errcd)
    }
}
//******************************************************************************
// ALARM ON - ALARM ON - ALARM ON - ALARM ON - ALARM ON - ALARM ON - ALARM ON
//******************************************************************************
void on() {
    if (devUse == "Alarm Server") {return} //** v200
    if (!Ok2Run("AlarmON")) {return}
    String cname = devName.toUpperCase()   //** v200
    log.warn "TRIGGER ALARM on " + cname
    if (device.currentValue("AlarmOut") == "NA") {
        log.warn "Alarm Out Feature is excluded or not available"
        return}
    SwitchAlarm("active")
}
//******************************************************************************
// ALARM OFF - ALARM OFF - ALARM OFF - ALARM OFF - ALARM OFF - ALARM OFF
//******************************************************************************
void off() {
    if (devUse == "Alarm Server") {return} //** v200
    if (!Ok2Run("AlarmOFF")) {return}
    String cname = devName.toUpperCase()   //** v200
    log.warn "CLEAR ALARM on " + cname
    if (device.currentValue("AlarmOut") == "NA") {
        log.warn "Alarm Out Feature is excluded or not available"
        return}
    SwitchAlarm("inactive")
}
//******************************************************************************
// ENABLE - ENABLE - ENABLE - ENABLE - ENABLE - ENABLE - ENABLE - ENABLE - ENABLE
//******************************************************************************
void Enable(String filter) {
    if (devUse == "Alarm Server") {return} //** v200
    if (!Ok2Run("Enable")) {return}
    String cname = devName.toUpperCase()   //** v200
    log.info "ENABLE $cname with filter: " + filter
    SwitchAll(filter, "true")
    return
}
//******************************************************************************
// DISABLE - DISABLE - DISABLE - DISABLE - DISABLE - DISABLE - DISABLE - DISABLE
//******************************************************************************
void Disable(String filter) {
    if (devUse == "Alarm Server") {return} //** v200
    if (!Ok2Run("Disable")) {return}
    String cname = devName.toUpperCase()   //** v200
    log.info "DISABLE $cname with filter: " + filter
    SwitchAll(filter, "false")
    return
}
//****************************************************************************** v110 START
// PUSH - PUSH - PUSH - PUSH - PUSH - PUSH - PUSH - PUSH - PUSH - PUSH - PUSH
//******************************************************************************
void push (buttonNumber) {
    if (devUse == "Controller") {return}
    if (!Ok2Run("Push")) {return}
    if (buttonNumber == null) {return}
    if (buttonNumber < 1 || buttonNumber > 8) {return}
    String cname = devName.toUpperCase()
    sendEvent(name: "pushed", value: buttonNumber, isStateChange: true)
    state.LastButtonPush = buttonNumber.toString()
    log.info "BUTTON PUSHED on " + cname + ": " + buttonNumber
}
//****************************************************************************** V1.1.0 END
// OK2RUN - OK2RUN - OK2RUN - OK2RUN - OK2RUN - OK2RUN - OK2RUN - OK2RUN - OK2RUN
//******************************************************************************
def Ok2Run(String cmd) {
    String devstatus = device.currentValue("zDriver")
    if (devstatus == "FAILED") {
        log.warn "Not allowed to run: " + cmd
        return(false)}
    if (devstatus == "ERR") {
        log.warn "Not allowed to run: " + cmd + ". Fix problem and Save Preferences to reset."
        return(false)}
    if (devstatus == "CRED") {
        log.warn "Not allowed to run: " + cmd + ". Fix creds or config and Save Preferences to reset."
        return(false)}
    return(true)
}
//******************************************************************************
// SWITCH ALARM - SWITCH ALARM -SWITCH ALARM -SWITCH ALARM -SWITCH ALARM
//******************************************************************************
private SwitchAlarm(String newstate) {
    String errcd = ""
    String devstate = state.AlarmOut                      //** v200
    String devaistate = state.AlarmIn                     //** v200
    String camstate = ""
    String camaistate = ""
    String path = FeaturePaths.AlarmIO
    def xml = SendGetRequest(path, "GPATH")
    if (strMsg != "OK") {
        errcd = LogGETError()
        sendEvent(name:"zDriver",value:errcd)
        return(errcd)
    }
    camstate = xml.IOPortStatus[1].ioState.text()
    camaistate = xml.IOPortStatus[0].ioState.text()
    if (camstate == newstate) {
        log.info "OK, already " + newstate
        state.AlarmOut = newstate                                                    //** v200
        state.AlarmIn = camaistate                                                   //** v200
        if (camaistate == "active") {sendEvent(name:"switch",value:"on")}  //** v110
        else {sendEvent(name:"switch",value:"off")}                        //** v110
        sendEvent(name:"zDriver",value:"OK")
        return("OK")
    }
//  Current state is reported as active/inactive    
//  To send the trigger, we set the outputState to high/low
    if (newstate == "active") {newstate = "high"}
    if (newstate == "inactive") {newstate = "low"}

    // Can't get any easier than this...
    strXML = "<IOPortData version=\"1.0\" xmlns=\"http://www.hikvision.com/ver10/XMLSchema\">" +\
"<outputState>" + newstate + "</outputState></IOPortData>"

    path = FeaturePaths.AlarmOut

    xml = SendPutRequest(path, strXML)

    if (strMsg == "OK") {
        if (newstate == "high") {newstate = "active"} else {newstate = "inactive"}
        log.info "OK, Alarm Out is now " + newstate
        state.AlarmOut = newstate                                             //** v200
        sendEvent(name:"zDriver", value:"OK")
        // Need to wait for the straggler Alarm alerts to be processed
        // before reseting AlarmIn on the device, otherwise it just gets triggered again
        // by the Alarm Server, leaving an out of sync condition. Play it safe, wait 60.
        if (newstate == "inactive" && state.AlarmSvr == "OK") {
            log.info "Waiting 1 minute for Alarm Event messages from camera to stop"
            runIn(60, RefreshAlarmStates, overwrite)
        } else {
            runIn(5, RefreshAlarmStates, overwrite)
        }
        return("OK")
    }
    log.error "PUT Error: " + strMsg
    if (strMsg.contains("code: 403") && strMsg.contains("Forbidden")) {
        log.error "Operator does not have Remote Parameters or Remote Notify options selected"
        sendEvent(name:"zDriver",value:"CRED")
        return("CRED")
    }
    sendEvent(name:"zDriver",value:"ERR")
    return("ERR")    
}
//******************************************************************************
// REFRESH ALARM STATES - REFRESH ALARM STATES - REFRESH ALARM STATES
//******************************************************************************
void RefreshAlarmStates() {
    log.info "Refreshing Alarm I/O states"
    String errcd = GetSetFeatureState("AlarmIO")
    if (errcd != "OK") {
        log.error strMsg
        sendEvent(name:"zDriver",value:errcd)
    }
}
//******************************************************************************
// SWITCH ALL - SWITCH ALL - SWITCH ALL - SWITCH ALL - SWITCH ALL - SWITCH ALL
//******************************************************************************
void SwitchAll(String filter, String newstate) {
    String errcd = " "
    String Path = ""

    if (filter == null) {filter = ""}

//  AlarmInH will only be switched if specified in the filter, by design
    if (filter != "" && filter.contains("ai")) {
        if (device.currentValue("AlarmInH") != "NA" || state.AlarmInH != "NA") { //** v200
            errcd = SetFeatureState("AlarmInH",newstate)
            if (errcd != "OK") {return}
        }
    } else {
        if (filter.contains("ai")) {log.info "Requested feature *ai* is NA"}
    }

    errcd = "OK"
    for (feature in MotionFeatures) {
        if (filter == "" || filter.contains("$feature.key")) {
            if (state."$feature.value" != "NA") {                        //** v200
                if (state."$feature.value" == null) {SavingPreferences = true}  //** v200
                errcd = SetFeatureState("$feature.value",newstate)
                SavingPreferences = false                                //** v200
                if (errcd == "NA") {                                     //** v200
                    state."$feature.value" = "NA"                        //** v200
                    errcd = "OK"                                         //** v200
                }                                                        //** v200 
                if (errcd != "OK") {break}
            } else {
                if (filter.contains("$feature.key")) {log.info "Requested feature *$feature.key* is NA"}
            }
        }
    }
    sendEvent(name:"zDriver",value:errcd)
    return        
}
//******************************************************************************
// SET FEATURE STATE - SET FEATURE STATE - SET FEATURE STATE - SET FEATURE STATE
//******************************************************************************
private SetFeatureState(String Feature, String newstate) {
    String errcd = ""
    String devstate = state."$Feature"                                  //** v200
    String Path = FeaturePaths."$Feature"
    def xml = SendGetRequest(Path, "XML")
    if (strMsg != "OK") {
        errcd = LogGETError()
        sendEvent(name:"zDriver", value:errcd)
        return(errcd)
    }
    // Find first occurence, Line Cross and Intrusion have sub-features
    // that also include the enabled element.
    def i = xml.indexOf("<enabled>")
    // this should never happen, cya
    if (i == -1) {
        strMsg = "Unexpected XML structure, <enabled> element not found"
        log.error strMsg
        sendEvent(name:"zDriver", value:"ERR")
        return("ERR")
    }
    // Extract current state from xml, first 4
    String camstate = xml.substring(i+9,i+13)
    if (camstate == "fals") {camstate = "false"}

    // ditto, cma
    if (camstate != "true" && camstate != "false") {
        log.error "inSetFeatureState: XML <enabled> element is not true/false"
        log.error "inSetFeatureState: Extracted <enabled>=" + camstate
        sendEvent(name:"zDriver", value:"ERR")
        return("ERR")
    }
    if (camstate == newstate) {
        if (newstate == "true") {newstate = "enabled"} else {newstate = "disabled"}
        log.info "OK, " + Feature + " is already " + newstate
        state."$Feature" = newstate                                     //** v200
        sendEvent(name:"zDriver",value:"OK")
        return("OK")
    }
    if (newstate == "true") {
        xml = xml.replaceFirst("<enabled>false<", "<enabled>true<")
    } else {
        xml = xml.replaceFirst("<enabled>true<", "<enabled>false<")
    }
    if (debug) {log.info "PUT " + Path + "/enabled=" + newstate}

    xml = SendPutRequest(Path, xml)

    if (strMsg == "OK") {
        if (newstate == "true") {newstate = "enabled"} else {newstate = "disabled"}
        log.info "OK, " + Feature + " is now " + newstate
//      sendEvent(name:"$Feature",value:newstate)                      //** v200
        state."$Feature" = newstate                                    //** v200
        sendEvent(name:"zDriver",value:"OK")
        return("OK")
    }
    log.error "PUT Error: " + strMsg
    if (strMsg.contains("code: 403")) {
        log.error "Operator does not have Remote Parameters or Remote Notify options selected"
        sendEvent(name:"zDriver",value:"CRED")
        return("CRED")
    }
    sendEvent(name:"zDriver",value:"ERR")
    return("ERR")    
}
//******************************************************************************
// SEND GET REQUEST - SEND GET REQUEST - SEND GET REQUEST - SEND GET REQUEST
//******************************************************************************
// Return XML OR GPATH with strMsg=OK, or strMsg=Error message
private SendGetRequest(String path, String rtype) {
    String credentials = device.getDataValue("CamID")
    def headers = [:] 
    def parms = [:]
    def xml = ""
    headers.put("HOST", devIP + ":" + devPort)
    headers.put("Authorization", "Basic " + credentials)
    parms.put("uri", "http://" + devIP + ":" + devPort + path)
    parms.put("headers", headers)
    parms.put("requestContentType", "application/xml")
    if (rtype == "XML") {parms.put ("textParser", true)}
    if (debug) {log.debug "GET  ${path}"}
    try {httpGet(parms) 
        { response ->
            if (debug) {
                log.debug "GET response.status: " + response.getStatus()
                log.debug "GET response.contentType: " + response.getContentType()
            }
            if (response.status == 200) {
                strMsg = "OK"
                if (rtype == "GPATH") {
                    xml = response.data
                    if (debug) {xml.'**'.each { node ->
                                log.debug "GPATH: " + node.name() + ": " + node.text()}}
                } else {
                    xml = response.data.text
                    if (debug) {log.debug groovy.xml.XmlUtil.escapeXml(xml)}
                }
            } else {
                strMsg = response.getStatus()
            }
        }}
    catch (Exception e) {
        strMsg = e.message
    }
    return(xml)
}
//******************************************************************************
// LOG GET ERROR - LOG GET ERROR - LOG GET ERROR - LOG GET ERROR
//******************************************************************************
private LogGETError() {
    //************************************************************* v200 START
    String cname = devName.toUpperCase()
    String errcd = "ERR"
    if (strMsg.contains("code: 403")) {
        if (!SavingPreferences) {
            log.error "GET Error: " + strMsg
            log.warn "Resource not available or is restricted at a higher level"
        }
        return("NA")
    }
    log.error "GET Error: " + strMsg
    if (strMsg.contains("No route to host") || strMsg.contains ("connect timed out")) {
        log.warn "$cname is OFFLINE"
        return("OFF")
    }
    //************************************************************* v200 END
    if (strMsg.contains("code: 401")) {
        log.warn "4) Operator Account requires Remote Parameters/Settings and Remote Notify options selected"
        log.warn "3) Credentials do not match or have been changed on the camera since last Save Preferences"
        log.warn "2) Network > Advanced > Integration Protocol > CGI Enabled w/Authentication=Digest/Basic"
        log.warn "1) System > Security > Web Authentication=Digest/Basic"
        log.warn "Authentication Failed, check the following:"
        errcd = "CRED"
    }
    if (strMsg.contains("code: 404")) {
        log.warn "Check Network > Advanced > Integration Protocol > CGI Enabled w/Authentication=Digest/Basic"
        log.warn "Hikvision-CGI is NOT ENABLED or IP is not a Hikvision camera"
    }
    return(errcd)
}
//******************************************************************************
// SEND PUT REQUEST - SEND PUT REQUEST - SEND PUT REQUEST - SEND PUT REQUEST
//******************************************************************************
// Return strMsg=OK or strMsg=Error message
private SendPutRequest(String strPath, String strXML) {
    def xml = ""
    def credentials = device.getDataValue("CamID")
    def headers = [:] 
    def parms = [:]

    headers.put("HOST", devIP + ":" + devPort)
    headers.put("Authorization", "Basic " + credentials)
    headers.put("Content-Type", "application/xml")

    parms.put("uri", "http://" + devIP + ":" + devPort + strPath)
    parms.put("headers", headers)
    parms.put("body", strXML)
    parms.put("requestContentType", "application/xml")

    try {httpPut(parms) { response ->
        if (debug) {
            log.debug "PUT response.status: " + response.getStatus()
            log.debug "PUT response.contentType: " + response.getContentType()
        }
        if (response.status == 200) {
            xml = response.data
            strMsg = "OK"}
         else {
            strMsg = response.getStatus()
         }
    }}
    catch (Exception e) {
        strMsg = e.message
    }
}
//******************************************************************************
// PARSE - PARSE - PARSE - PARSE - PARSE - PARSE - PARSE - PARSE - PARSE - PARSE
//******************************************************************************
void parse(String description) {
    String etype = ""    // eventType
    String estate = ""   // eventState
    String evnum = ""    // event button number //** v110
    String lastpush = ""  // LastButtonsPushed
    String ecode = ""    // event type feature code //** v200
    Boolean ok = false   // Only Supported Motion Events trigger motion on this device
    Boolean ns = false   // Not Supported and Unknown Events are logged and ignored
    String logtag = ""   // Logging tag for new Motion/Duration Event - v106

    String cname = devName.toUpperCase() //** v200

    if (device.currentValue("zDriver") == "OFF") {
        log.warn "$cname is BACK ONLINE"
        sendEvent(name:"zDriver",value:"OK")
    }
    // Initialize on first event message
    if (state.AlarmSvr == null || state.AlarmSvr == "NA") {
        log.warn "Alarm Server NOW IN USE on " + cname
        log.info "Initializing State variables"
        state.AlarmSvr = "OK"
        state.LastAlarm = "na"
        state.AlarmCount = 0
        state.LastMotionEvent = "na"
        state.LastMotionTime = "na"
        state.LastButtonPush = "na" //** v110
        state.MotionEventCount = 0
        state.OtherEventState = "inactive"
        state.LastOtherEvent = "na"
        state.LastOtherTime = "na"
        state.OtherEventCount = 0
        state.EventMsgCount = 0
        state.ExcludedEvents = 0 //** v200
        if (devResetCounters == "Daily") {schedule('0 0 1 * * ?',ResetCounters)}
        if (devResetCounters == "Weekly") {schedule('0 0 1 ? * 1',ResetCounters)}
        if (devResetCounters == "Monthly") {schedule('0 0 1 1 * ?',ResetCounters)}
    }
    def rawmsg = parseLanMessage(description)   
    def hdrs = rawmsg.headers  // its a map
    def msg = ""               // tbd
    if (debuga) {log.warn "EVENT MESSAGE RECEIVED"} //** v106
    if (debuga) {hdrs.each {log.debug it}}
    // This is the key to knowing what you have to work with
    if (hdrs["Content-Type"] == "application/xml; charset=\"UTF-8\"" || hdrs["Content-Type"] == "application/xml") {
        msg = new XmlSlurper().parseText(new String(rawmsg.body))
        if (debuga) {log.debug "MSG:" + groovy.xml.XmlUtil.escapeXml(rawmsg.body)} //** v106
        estate = msg.eventState.text()
        etype = msg.eventType.text()
        if (debuga) {log.debug "msg.eventType.text: " + etype}
        if (debuga) {log.debug "msg.eventState.text: " + estate}
        // ******************************************************* v106 START
        if (etype == "duration") {
            logtag = "-Duration"
            etype = msg.DurationList.Duration.relationEvent.text()
            if (debuga) {log.debug "msg.DurationList.Duration.relationEvent.text: " + etype}
        }
        if (eetype == "") {eetype = "Unknown"}
        // ******************************************************** v106 END
        etype = ">" + etype + "<"
        for (event in SupportedEvents) {
            if (etype == event) {
                ok = true
                break}
        }
        if (!ok) {
            for (event in UnsupportedEvents) {
                if (etype == event) {
                    ns = true
                    break}
            }
        }
    } else {
        msg = rawmsg.body.toString()
        if (debuga) {log.debug "MSG:" + msg}
        for (event in SupportedEvents) {
            if (msg.contains("$event")) {
                etype = event
                ok = true
                break}
        }
        if (!ok) {
            for (event in UnsupportedEvents) {
                if (msg.contains("$event")) {
                    etype = event
                    ns = true
                    break}
            }
        }
    }
    if (!ok && !ns) {
        ns = true
        etype = "Unknown"
    } else {
        etype = etype.substring(1,etype.length()-1)
    }
    // Translate to user/driver friendly names
    etype = TranslateEvents."$etype"
    
    state.EventMsgCount = state.EventMsgCount + 1

    // For Unsuported Events, log the first occurence and ignore the rest
    if (ns) {
        if (state.OtherEventState == "inactive" || etype != state.LastOtherEvent) {
            log.warn "OTHER EVENT1 on " + cname + ": " + etype + logtag  //** v106
            state.OtherEventState = "active"
            state.LastOtherEvent = etype
            state.LastOtherTime = new Date().format ("EEE MMM d HH:mm:ss")
            state.OtherEventCount = state.OtherEventCount + 1
            // 1.0.5 and 1.0.6 Updates - Log Unknown Events
            if (etype == "Unknown") {
                if (!debuga) {
                    if (hdrs["Content-Type"] == "application/xml; charset=\"UTF-8\"" || hdrs["Content-Type"] == "application/xml") {
                        log.warn "EVENT MESSAGE:" + groovy.xml.XmlUtil.escapeXml(rawmsg.body)
                    } else {
                        log.warn "EVENT MESSAGE:" + rawmsg.body
                    }
                }
                log.warn "Please report this event to trsystems.help@gmail.com"
            }
        }
        // Give whatever this a minute to run its course
        // Most of these are one-time(?) but a few are ongoing like motion
        // And if thats the case, this will continue to get pushed out
        // Cant even test some of these unsupported events
        // And, all cameras behave differntly in terms of how many msgs they send
        // and the interval between while an event is in progress
        // One of my cameras sent 5 messages at 1 per second for each failed login
        runIn(60, ResetUsupEvent, overwrite)
        return
    }
    if (etype == "AlarmIn") {
        // First time in?
        if (state.AlarmIn == null || state.AlarmIn == "inactive") {  //** v200
            log.warn "ALARM INPUT on " + cname
            state.AlarmIn = "active"                           //** v200
            sendEvent(name:"switch",value:"on")                //** v110
            state.AlarmCount = state.AlarmCount + 1
            state.LastAlarm = new Date().format ("EEE MMM d HH:mm:ss")
            return
        }
        // Ignore the rest until it gets turned off
        // Reset is handled by the off command/SwitchAlarm method
        return
    }
    // Supported Motion Event
    // ************************************************************ v200 START
    ecode = FeatureNamesToCode."$etype"
    if (devExcludeA == null) {devExcludeA = ""}
    if (devExcludeA.contains("$ecode")) {
        state.ExcludedEvents = state.ExcludedEvents + 1
        if (state.LastExcluded == null || state.LastExcluded != etype) {
            state.LastExcluded = etype
            log.warn "EXCLUDED MOTION EVENT on " + cname + ": " + etype
        }
        return
    }
    // ************************************************************ v200 END
    if (device.currentValue("motion") == "inactive") {
        sendEvent(name:"motion",value:"active")
        state.LastMotionEvent = etype
        state.LastMotionTime = new Date().format ("EEE MMM d HH:mm:ss")
        state.MotionEventCount = state.MotionEventCount + 1
        // ******************************************************** v110 START
        evnum = EventButtonNumbers."$etype"
        state.LastButtonPush = evnum
        sendEvent(name:"pushed",value:evnum,isStateChange:true)  //** v200
        logtag = logtag + "-Push: " + evnum
        // ******************************************************** v110 END
        log.warn "MOTION EVENT1 on " + cname + ": " + etype + logtag //** v106
    } else {
        // We may have more than one going on
        if (etype != state.LastMotionEvent) {
            state.LastMotionEvent = etype
            state.LastMotionTime = new Date().format ("EEE MMM d HH:mm:ss")
            // ******************************************************** v110 START
            evnum = EventButtonNumbers."$etype"
            lastpush = state.LastButtonPush
            if (lastpush == null) {lastpush=""}
            if (!lastpush.contains("$evnum")) {
                lastpush = lastpush + "," + evnum
                state.LastButtonPush = "$lastpush"
                sendEvent(name:"pushed",value: evnum)  //** v200
                logtag = logtag + "-Push: " + evnum
            }
            // ******************************************************** v110 END
            log.info "MOTION EVENT+ on " + cname + ": " + etype + logtag // v.1.0.6
        }
    }
    // Wait minutes, not seconds for all motion events to clear
    // Avoid unnecessary motion state changes in HE
    // Typical PIR sensors will wait 4 minutes before signaling clear
    runIn (settings.devMotionReset.toInteger() * 60, ResetMotion, overwrite)
}	
void ResetMotion() {
    String cname = devName.toUpperCase() //** v200
    sendEvent(name:"motion",value:"inactive")
    log.info "MOTION CLEARED on " + cname
}
void ResetUsupEvent() {
    String cname = devName.toUpperCase() //** v200
    state.OtherEventState = "inactive"
    log.info "OTHER EVENT CLEARED on " + cname
}
void ResetCounters() {
    log.warn "Resetting Alarm Server Counters"
    state.AlarmCount = 0
    state.MotionEventCount = 0
    state.OtherEventCount = 0
    state.EventMsgCount = 0
    state.ExcludedEvents = 0
}
//*****************************************************************
// Static Constants
//*****************************************************************
@Field static List SupportedEvents = [
    ">IO<",
    ">VMD<",
    ">PIR<",
    ">linedetection<",
    ">fielddetection<",
    ">regionEntrance<",
    ">regionExiting<",
    ">attendedBaggage<",
    ">unattendedBaggage<"
]
@Field static List UnsupportedEvents = [
    ">facedetection<",
    ">faceSnap<",
    ">loitering<",
    ">scenechangedetection<",
    ">storageDetection<",
    ">badvideo<",
    ">diskerror<",
    ">diskfull<",
    ">illAccess<",
    ">ipconflict<",
    ">nicbroken<",
    ">tamperdetection<",
    ">videomismatch<"
]
@Field static Map TranslateEvents = [
    IO:"AlarmIn",
    VMD:"Motion",
    PIR:"PIR",
    linedetection:"LineCross",
    fielddetection:"Intrusion",
    attendedBaggage:"ObjectR",
    regionEntrance:"RgnEnter",
    regionExiting:"RgnExit",
    unattendedBaggage:"UBaggage",
    // unsupported
    facedetection:"FaceD",
    faceSnap:"FaceSnap",
    loitering:"Loitering",
    scenechangedetection:"SceneChg",
    storageDetection:"Storage",
    badvideo:"BadVideo",
    diskerror:"DiskError",
    diskfull:"DiskFull",
    illAccess:"LoginFailed",
    tamperdetection:"Tamper",
    Unknown:"Unknown"
]
@Field static List FeatureCodes = ["ai","ao","in","lc","m","p","or","re","rx","ub"]
@Field static Map FeatureCodesToName = [
    ai:"AlarmInH",
    ao:"AlarmIO",
    in:"Intrusion",
    lc:"LineCross",
    m:"MotionD",
    p:"PIRSensor",
    or:"ObjectR",
    re:"RgnEnter",
    rx:"RgnExit",
    ub:"UBaggage"
    ]
@Field static Map MotionFeatures = [
    in:"Intrusion",
    lc:"LineCross",
    m:"MotionD",
    p:"PIRSensor",
    or:"ObjectR",
    re:"RgnEnter",
    rx:"RgnExit",
    ub:"UBaggage"
    ]
@Field static Map FeatureNamesToCode = [
    Intrusion:"in",
    LineCross:"lc",
    MotionD:"m",
    PIRSensor:"p",
    ObjectR:"or",
    RgnEnter:"re",
    RgnExit:"rx",
    UBaggage:"ub"
    ]
// ****************************************** v110 START
@Field static Map EventButtonNumbers = [
    Intrusion:"1",
    LineCross:"2",
    Motion:"3",     //** v200 bug fix
    PIR:"4",        //** v200 bug fix
    ObjectR:"5",
    RgnEnter:"6",
    RgnExit:"7",
    UBaggage:"8"
    ]
// ****************************************** v110 END
// Includes paths to system info and features not implemented
@Field static Map FeaturePaths = [
    SysInfo:"/ISAPI/System/deviceInfo",
    Network:"/ISAPI/System/Network/Interfaces/1",
    CamUsers:"/ISAPI/Security/users",
    UserPerm:"/ISAPI/Security/UserPermission/",
    AlarmSvr:"/ISAPI/Event/notification/httpHosts",
    AlarmInH:"/ISAPI/System/IO/inputs/1",
    AlarmIO:"/ISAPI/System/IO/status",
    AlarmOut:"/ISAPI/System/IO/outputs/1/trigger",
    Intrusion:"/ISAPI/Smart/FieldDetection/1",
    LineCross:"/ISAPI/Smart/LineDetection/1",
    MotionD:"/ISAPI/System/Video/inputs/channels/1/motionDetection",
    ObjectR:"/ISAPI/Smart/attendedBaggage/1",
    PIRSensor:"/ISAPI/WLAlarm/PIR",
    RgnEnter:"/ISAPI/Smart/regionEntrance/1",
    RgnExit:"/ISAPI/Smart/regionExiting/1",
    UBaggage:"/ISAPI/Smart/unattendedBaggage/1",
    Face:"/ISAPI/Smart/FaceDetect/1",
    Loitering:"/ISAPI/Smart/loitering/1",
    PeopleD:"/ISAPI/Smart/peopleDetection",
    SceneChg:"/ISAPI/Smart/SceneChangeDetection/1"
]
