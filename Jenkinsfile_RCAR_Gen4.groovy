import groovy.transform.Field
import groovy.json.JsonSlurper

def CICDRootPath = "${env.CICD_PATH}" 
def jobPath = "${CICDRootPath}\\02_Jobs\\50_UT\\03_DUT"
def libPath = "${CICDRootPath}\\02_Jobs\\99_Library"

def skipRemainingStages = false
def VALUE_REPO
// def GLB_SVN_REPO
def GLB_MSN
def GLB_DEVICE
def GLB_MODULE_REPO
def GLB_GENERIC_REPO
def GLB_GIT_MASTERS
def GLB_DEVICE_VARIANT
def config_set
def device_set
def msnLower
def MSNUpper

node (params.NODE) {
    withEnv(["libPath=${libPath}"]){
        stage('Get_Parametter') {
            ws (CICDRootPath) {
                checkout scm
                commonLib= load "${libPath}/CommonConfiguration.groovy"
                def commonData="${libPath}/RCAR_G4_DefineParametter.json"
                def parseJson= readJSON(file: "${commonData}")
                // GLB_DEVICE=parseJson.parametter_all.MICRO_SUB_VARIANT
                GLB_GIT_MASTERS=parseJson.parametter_all.GIT_MASTERS
                if (env.JOB_NAME.contains("V4")) (
                    GLB_DEVICE_VARIANT = ['V4H']
                ) else if(env.JOB_NAME.contains("S4")) {
                    GLB_DEVICE_VARIANT = ['S4']
                }
            }
        }
    }
}
properties([
    parameters([
        //choice(name: 'NODE', choices: ['410268','410346','410798'], description: '')
        choice(name: 'NODE', choices: ['412088','412543', '412101'], description: 'The node name to execuate DUT'),
        choice(name: 'DEVICE_VARIANT', choices: GLB_DEVICE_VARIANT, description: 'Choose device variant to execute. This setting must compatible with DEVICE_SUB_VARIANT choosing'),
        [$class: 'CascadeChoiceParameter', 
            choiceType: 'PT_MULTI_SELECT',
            description: 'Choose the device sub variant. Hold Ctrl and click to select multiple item',
            name: 'DEVICE_SUB_VARIANT',
            referencedParameters: 'DEVICE_VARIANT',
            script: [$class: 'GroovyScript',
                fallbackScript: [
                    classpath: [], 
                    sandbox: true, 
                    script: 'return ["ERROR"]'
                ],
                script: [
                    classpath: [], 
                    sandbox: true, 
                    script: """
                        if (DEVICE_VARIANT=="S4") {
                            return ["S4_G4MH","S4_CR52"]
                        }
                        else if (DEVICE_VARIANT=="V4H") {
                            return ["V4H"]
                        }
                        else {
                            return ["N/A"]
                        }
                    """.stripIndent()
                ]
            ]
        ],
        extendedChoice (defaultValue: 'All', description: 'To choose config to execute. Hold Ctrl and click to choose multiple config. If choose "All", all config coresspond to DEVICE_SUB_VARIANT in repo will be used ', 
        multiSelectDelimiter: ',', name: 'CONFIG', type: 'PT_MULTI_SELECT', value: 'All,CFG01,CFG02,CFG03,CFG04,CFG05,CFG06,CFG07,CFG08,CFG09,CFG10,CFG11,CFG12,CFG13,CFG14,CFG15,CFG16,CFG17,CFG18,CFG19,CFG20,CFG21,CFG22,CFG23,CFG24,CFG25,CFG26,CFG27,CFG28,CFG29', 
        visibleItemCount: 10),
        choice(name: 'GIT_MASTER', choices: GLB_GIT_MASTERS, description: 'Choose which repository to build DUT job.\nREL_GIT: git@rcar-env.dgn.renesas.com:mcal/rcar/mcal_cpf.git\nRVC_GIT_NEW: git@gitlab.rvc.renesas.com:AUTOSAR/mcal_cpf.git\nRVC_GIT: git@gitlab.rvc.renesas.com/AUTOSAR/R-CarGen3_AUTOSAR.git'),
        [$class: 'CascadeChoiceParameter', 
            choiceType: 'PT_RADIO',
            description: 'Branch name to build DUT job. Ctrl + F to search.',
            name: 'GIT_BRANCH',
            referencedParameters: 'GIT_MASTER',
            script: [$class: 'GroovyScript',
                fallbackScript: [
                    classpath: [], 
                    sandbox: true, 
                    script: 'return ["ERROR"]'
                ],
                script: [
                    classpath: [], 
                    sandbox: false, 
                    script: '''
                        def GIT_REPO=""
                        if (GIT_MASTER=="REL_GIT") {
                        GIT_REPO="https://hoang.dang.fv%40renesas.com:hoagkub.2479.A@rcar-env.dgn.renesas.com/gitlab/mcal/rcar/mcal_cpf.git"
                        } else if (GIT_MASTER=="RVC_GIT") {
                        GIT_REPO="https://hoangdang:hoagkub.2479.A@gitlab.rvc.renesas.com/AUTOSAR/R-CarGen3_AUTOSAR.git"
                        } else if (GIT_MASTER=="RVC_GIT_NEW") {
                        GIT_REPO="https://hoangdang:hoagkub.2479.A@gitlab.rvc.renesas.com/AUTOSAR/mcal_cpf.git"
                        }
                        def GET_LIST=("git ls-remote --heads --tags ${GIT_REPO}").execute()
                        BRANCH_LIST=GET_LIST.in.text.readLines().collect {it.split()[1].replaceAll('refs/heads/', '').replaceAll('refs/tags/', '').replaceAll("\\\\^\\\\{\\\\}", '')}
                        return BRANCH_LIST
                    '''.stripIndent()
                ]
            ]
        ],
        choice(name: 'GIT_INPUT_TYPE', choices: ["BRANCH","TAG"], description: "BRANCH:use input GIT_BRANCH value to checkout as branch name\nTAG: use input GIT_BRANCH value to checkout as Tag label"),
        choice(name: 'INT_SVN', choices: ["TRUE","FALSE"], description: 'If FALSE internal get from GIT, otherwise get from SVN (http://rb03988.rsd.renesas.com/subversion/mcal_cpf/trunk)'),
        string(name: 'EMAIL_ADD', defaultValue: "", description: 'Email address for user, who wants to get the output file'),
        [$class: 'CascadeChoiceParameter', 
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Module Short Name.',
            name: 'MSN',
            referencedParameters: 'DEVICE_SUB_VARIANT',
            script: [$class: 'GroovyScript',
                fallbackScript: [
                    classpath: [], 
                    sandbox: true, 
                    script: 'return ["ERROR"]'
                ],
                script: [
                    classpath: [], 
                    sandbox: true, 
                    script: """\
                        if (DEVICE_SUB_VARIANT=="S4_G4MH") {
                            return ["Can", "Dio", "Eth", "Fls", "Fr", "Gpt", "Cddiccom", "Icu", "Lin", "Mcu", "Port", "Pwm", "Spi", "Wdg"]
                        }
                        else if (DEVICE_SUB_VARIANT=="S4_CR52") {
                            return ["Can", "Dio", "Eth", "Gpt", "Cddiccom", "Mcu", "Port"]
                        }
                        else if(DEVICE_SUB_VARIANT =="V4H"){
                            return ["Can", "Dio", "Eth", "Fls", "Gpt", "Cddiccom", "Mcu", "Port", "Spi", "Wdg", "Cddcrc", "Cddths", "Cddipmmu", "Cddemm", "Cddrfso", "Cddiic"]
                        } else if (DEVICE_SUB_VARIANT=="S4_G4MH,S4_CR52") {
                            return["Can", "Dio", "Eth", "Fls", "Fr", "Gpt", "Cddiccom", "Icu", "Lin", "Mcu", "Port", "Pwm", "Spi", "Wdg"]
                        } else if (DEVICE_SUB_VARIANT=="S4_G4MH,V4H") {
                            return["Can", "Dio", "Eth", "Fls", "Fr", "Gpt", "Cddiccom", "Icu", "Lin", "Mcu", "Port", "Pwm", "Spi", "Wdg", "Cddcrc", "Cddths", "Cddipmmu", "Cddemm", "Cddrfso", "Cddiic"]
                        } else if (DEVICE_SUB_VARIANT=="S4_CR52,V4H") {
                            return["Can", "Dio", "Eth", "Fls", "Gpt", "Cddiccom", "Mcu", "Port", "Spi", "Wdg", "Cddcrc", "Cddths", "Cddipmmu", "Cddemm", "Cddrfso", "Cddiic"]
                        } else if (DEVICE_SUB_VARIANT=="S4_G4MH,S4_CR52,V4H") {
                            return["Can", "Dio", "Eth", "Fls", "Fr", "Gpt", "Icu", "Lin", "Mcu", "Port", "Pwm", "Spi", "Wdg", "Cddiccom", "Cddcrc", "Cddemm", "Cddiic", "Cddipmmu", "Cddrfso", "Cddths"]
                        } else if (DEVICE_SUB_VARIANT=="") {
                            return["Please select DEVICE_SUB_VARIANT"]
                        } else {
                            return ["Not yet supported!"]
                        }
                    """.stripIndent()
                ]
            ]
        ],
        
        choice(name: 'GET_LATEST_SOURCE', choices: ["NO","YES"], description: 'If YES, job will scan the .zip test app for each config then copy source file (.c,.h) from external to replace files in test app\nIf NO, job will run with .c, .h file from .zip test app files only')
    ])
])


pipeline {
    agent {
        node {
            label "${env.NODE}"
            customWorkspace jobPath
        }
    }
    options { skipDefaultCheckout() 
              timeout(time:4, unit: 'HOURS')}
    environment {
        jobPath = "${jobPath}"
        libPath = "${libPath}"
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    // format the module name
                    if (params.MSN.contains("Cdd")){
                        MSNUpper = "${params.MSN.take(3).toUpperCase()}_${params.MSN.drop(3).take(params.MSN.length()-3).toUpperCase()}"
                    } else {
                        MSNUpper = params.MSN.toUpperCase()
                    }
                    msnLower = params.MSN.toLowerCase()
                    //Checkout repo as params choose and map U
                    commonLib.checkout_repo(params.GIT_MASTER, params.GIT_BRANCH, params.INT_SVN, params.MSN, "DUT", "rcar_gen4", params.GIT_INPUT_TYPE)
                    //regen gentool output files
                    bat(script: "python38 -u scripts/Regenerate_Gentool.py ${params.MSN} rcar ${params.DEVICE_VARIANT} \"${params.DEVICE_SUB_VARIANT}\" \"${params.CONFIG}\" --mode validate")
                    //regen config path and folder structure for each config 
                    bat(script: "python38 -u scripts/RegenerateCanttDir.py ${params.MSN} rcar")
                    def properties = readFile (file: 'VARIABLE_PROPERTIES.properties') 
                    def configset_data = properties =~ "CFG_SET=(.*)"
                    config_set = configset_data[0][1].trim()
                    def device_data = properties =~ "DEVICE_SET=(.*)\n" 
                    device_set = device_data[0][1].trim()

                }
                script {
                    // compare the config set scanned from cfg folder and in .zip file, detect the valid config.
                    bat(script: "python38 -u scripts/CheckConfig.py ${msnLower} \"${config_set}\"")
                    def properties = readFile (file: 'CONFIG.properties') 
                    def configset_data = properties =~ "CFG_SET=(.*)"
                    config_set = configset_data[0][1].trim()
                    currentBuild.displayName = "#${currentBuild.number}_${params.MSN}_${device_set}"
                    currentBuild.description = """\
                    <pre>
                    NODE: ${params.NODE}
                    GIT_MASTER: ${params.GIT_MASTER}
                    GIT_BRANCH: ${params.GIT_BRANCH}
                    INTERNAL_SVN: ${params.INT_SVN}
                    EMAIL_ADDL: ${params.EMAIL_ADD}
                    DEVICE_VARIANT : ${params.DEVICE_VARIANT}
                    DEVICE_SUB_VARIANT: ${params.DEVICE_SUB_VARIANT}
                    MSN: ${params.MSN}
                    CONFIG: ${config_set}
                    GET_LATEST_SOURCE: ${params.GET_LATEST_SOURCE}""".stripIndent()
                }
            }
            post {
                always{
                    script {
                        def log = currentBuild.rawBuild.log
                        def match = log =~ "svn:\\s+E"
                        if(match){
                            currentBuild.result = "UNSTABLE"
                            echo("[+]JK_WARNING: The repo can't update")
                        }
                    }
                }
            }
        }
        stage('Running Unit Test') {
            steps {
                script {
                    bat(script: "./scripts/DUT_RCAR_Automation_Running.bat \"${device_set}\" ${msnLower} ${jobPath} ${MSNUpper} \"${config_set}\" ${params.GET_LATEST_SOURCE}")
                }
            }
            post{
                always{
                    script {
                            def log = currentBuild.rawBuild.log
                            def license_error = commonLib.check_error(log, "All licensing tokens.*are already in use")
                            def err_flag = false
                            if(license_error){
                                err_flag = true
                            }
                            def python_error = commonLib.check_error(log, "Traceback (most recent call last).*")
                            if(python_error){
                                print("[x] JK_ERROR: Exception occur when execute init.py, please check the log for more information")
                                err_flag = true
                            }
                            def multi_error = commonLib.check_multi_error(log,"Error: Cannot open ctr file.*|undefined reference to.*|error: I9136.*|fatal error:.*|Error I\\d+.*")
                            if (err_flag == true || multi_error == true){
                                commonLib.SetResult("FAILURE","FAILURE")
                                commonLib.send_mail(params.EMAIL_ADD)
                            }
                    }
                }
            }
        }
        stage('Run Cantata Extract Cov To Docx') {
            when{ expression { currentBuild.result != 'FAILURE'}}
            steps {
                script {
                    bat(script: "python38 -u scripts/CopyCtr_GenDocFile.py ${params.MSN} rcar")
                    //bat(script: "./scripts/DUT_RCAR_Cov2Doc.bat \"${device_set}\" ${msnLower} ${jobPath} ${MSNUpper} \"${config_set}\"")
                }
            }
            post{
                always{
                    script {
                            def log = currentBuild.rawBuild.log
                            def license_error = commonLib.check_error(log, "All licensing tokens.*are already in use")
                            if(license_error){
                                commonLib.SetResult("FAILURE","FAILURE")
                                commonLib.send_mail(params.EMAIL_ADD)
                            }
                            def python_error = commonLib.check_error(log, "Traceback (most recent call last).*")
                            if(python_error){
                                commonLib.SetResult("FAILURE","FAILURE")
                                print("[x] JK_ERROR: Exception occur when execute python, please check the log for more information")
                                commonLib.send_mail(params.EMAIL_ADD)
                                }
                            }
                }
            }
        }
        stage('Merge Docx and Parse to Excel') {
            when{ expression { currentBuild.result != 'FAILURE'}}
            steps {
                script {
                    bat(script: "./scripts/DUT_RCAR_Doc2Excel.bat ${msnLower}")
                }
            }
            post{
                always{
                    script {
                            def log = currentBuild.rawBuild.log
                            def error1 = commonLib.check_error(log, / [A-Za-z0-9^\]^:]+:.*\[+Errno 2+\].*/)
                            def error2 = commonLib.check_error(log, / [A-Za-z0-9^\]^:]+:.*\[+WinError 2+\].*/)
                            def error3 = commonLib.check_error(log, / [A-Za-z0-9^\]^:]+:.*\[+WinError 3+\].*/)
                            if(error1||error2||error3){
                                commonLib.SetResult("FAILURE","FAILURE")
                                commonLib.send_mail(params.EMAIL_ADD)
                            }
                    }
                }
            }
        }
        stage('Report') {
            when{ expression { currentBuild.result != 'FAILURE'}}
            steps {
                script {
                        bat(script: "python38 ${jobPath}\\scripts\\DUT_RCAR_report.py ${params.MSN}")
                }
            }
        }
        stage('Zip') {
            when {
                expression { return true } // always do this stage
            }
            steps {
                script {
                        bat(script: "./scripts/DUT_RCAR_ZipReport.bat ${params.MSN} \"${config_set}\"")
                        commonLib.report_reading("DUT", "**/*report.xlsm", "**/Coverage_Report*.xlsm")
                }
            }
            post {
                always {
                    script {
                            archiveArtifacts "rcar_${params.MSN}_*.zip"
                            commonLib.send_mail(params.EMAIL_ADD)
                        }
                    }
            }
        }
        
    }
    post{
            aborted {
                script{
                    commonLib.send_mail(params.EMAIL_ADD)
                }
            }
        }
}

@NonCPS
def check_multi_error(log, pattern){
    def run_test_error = log =~ pattern
    def multi_err_flag =false
    if(run_test_error){
        (0..<run_test_error.count).each { print("[x] JK_ERROR: ${run_test_error[it]}")}
        multi_err_flag = true
    }
    return multi_err_flag
}
