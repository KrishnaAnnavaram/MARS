import groovy.json.JsonSlurper

pipeline {
    agent any

    stages {
        stage('Checkout') {
            steps {
                // Checkout your repository
                git branch: 'master', url: 'https://github.com/Vihanga22365/springboot-assignment.git'
            }
        }

        stage('Check Unit Testing %, Cucumber Test % and then Deploy Employee Service') {
            steps {
                script {

                    def coveragePercentage = findCoveragePercentage()
                    def cucumberTestResult = runCucumberTests()
                    echo "Unit Test Coverage: ${coveragePercentage}%"
                    echo "Cucumber Test Coverage: ${cucumberTestResult}%"

                    if (coveragePercentage > 30 && cucumberTestResult > 90) {
                        dir('employee-service') {
                            bat 'mvn clean compile package'
                            bat '"C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/bin/shutdown.bat"'
                            bat 'copy target\\employee-service-0.0.1-SNAPSHOT.war "C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/webapps/"'
                            bat '"C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/bin/startup.bat"'
                        }
                    } else {
                        echo "Unit test Percentage is below 30% or Cucumber test Percentage is below than 90% . Skipping deployment of Employee Service."
                    }
                }
            }
        }


        stage('Build and Deploy Discovery Service') {
            steps {
                dir('discovery-service') {
                    // Build the discovery-service using Maven
                    bat 'mvn clean compile package'

                    // Stop the local Tomcat server
                    bat '"C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/bin/shutdown.bat"' // Stop the Tomcat server

                    // Copy the newly built WAR file to the Tomcat webapps directory
                    bat 'copy target\\discovery-service-0.0.1-SNAPSHOT.war "C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/webapps/"'

                    // Start the Tomcat server
                    bat '"C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/bin/startup.bat"'
                }
            }
        }

        stage('Build and Deploy Configuration Service') {
            steps {
                dir('configuaration-server') {
                    // Build the discovery-service using Maven
                    bat 'mvn clean compile package'

                    // Stop the local Tomcat server
                    bat '"C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/bin/shutdown.bat"' // Stop the Tomcat server

                    // Copy the newly built WAR file to the Tomcat webapps directory
                    bat 'copy target\\configuaration-server-0.0.1-SNAPSHOT.war "C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/webapps/"'

                    // Start the Tomcat server
                    bat '"C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/bin/startup.bat"'
                }
            }
        }

        stage('Build and Deploy Department Service') {
            steps {
                dir('department-service') {
                    // Build the discovery-service using Maven
                    bat 'mvn clean compile package'

                    // Stop the local Tomcat server
                    bat '"C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/bin/shutdown.bat"' // Stop the Tomcat server

                    // Copy the newly built WAR file to the Tomcat webapps directory
                    bat 'copy target\\department-service-0.0.1-SNAPSHOT.war "C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/webapps/"'

                    // Start the Tomcat server
                    bat '"C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/bin/startup.bat"'
                }
            }
        }

//         stage('Build and Deploy Employee Service') {
//             steps {
//                 dir('employee-service') {
//                     // Build the discovery-service using Maven
//                     bat 'mvn clean compile package'
//
//                     // Stop the local Tomcat server
//                     bat '"C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/bin/shutdown.bat"' // Stop the Tomcat server
//
//                     // Copy the newly built WAR file to the Tomcat webapps directory
//                     bat 'copy target\\employee-service-0.0.1-SNAPSHOT.war "C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/webapps/"'
//
//                     // Start the Tomcat server
//                     bat '"C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/bin/startup.bat"'
//                 }
//             }
//         }

        stage('Build and Deploy Report Service') {
            steps {
                dir('report-service') {
                    // Build the discovery-service using Maven
                    bat 'mvn clean compile package'

                    // Stop the local Tomcat server
                    bat '"C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/bin/shutdown.bat"' // Stop the Tomcat server

                    // Copy the newly built WAR file to the Tomcat webapps directory
                    bat 'copy target\\report-service-0.0.1-SNAPSHOT.war "C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/webapps/"'

                    // Start the Tomcat server
                    bat '"C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/bin/startup.bat"'
                }
            }
        }

        stage('Build and Deploy Scheduler Service') {
            steps {
                dir('sheduler-service') {
                    // Build the discovery-service using Maven
                    bat 'mvn clean compile package'

                    // Stop the local Tomcat server
                    bat '"C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/bin/shutdown.bat"' // Stop the Tomcat server

                    // Copy the newly built WAR file to the Tomcat webapps directory
                    bat 'copy target\\sheduler-service-0.0.1-SNAPSHOT.war "C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/webapps/"'

                    // Start the Tomcat server
                    bat '"C:/Program Files/Apache Software Foundation/Tomcat 8.5_Tomcat8.1/bin/startup.bat"'
                }
            }
        }

        stage('Cleanup') {
            steps {
                // Clean up any artifacts or temporary files if needed
                deleteDir()
            }
        }
    }
}

def findCoveragePercentage() {
    dir('employee-service') {
        bat 'mvn clean test -P unitTest'
        def coverageReport = readFile(file: 'target/site/jacoco/index.html')

        def pattern = /<td class="ctr2">([\d.]+)%<\/td>/
        def matcher = (coverageReport =~ pattern)
        if (matcher.find()) {
            return matcher.group(1).toFloat()
        } else {
            return 0
        }
    }
}

def runCucumberTests() {
    dir('employee-service') {
        bat 'mvn clean verify -P cucumberTest'

        def cucumberJsonFile = 'target/cucumber.json';
        def cucumberJsonContent = readFile(cucumberJsonFile)
        def json = readJSON(text: cucumberJsonContent)
        def totalScenarios = json[0].elements.size()
        def passedScenarios = json[0].elements.count { it.steps.every { it.result.status == 'passed' } }
        def failedScenarios = json[0].elements.findAll { it.steps.any { step -> step.result.status == 'failed' } }

        echo "totalScenarios ${totalScenarios}"
        echo "passedScenarios ${passedScenarios}"

        for (int index = 0; index < failedScenarios.size(); index++) {
            def scenario = failedScenarios[index]
            def scenarioName = scenario.name
            def failedStep = scenario.steps.find { it.result.status == 'failed' }
            def failedStepName = failedStep.name
            def error = failedStep.result.error_message

            echo "(${index + 1}) Failed Scenario Name: ${scenarioName}"
            echo "(${index + 1}) Failed Step: ${failedStepName}"
            echo "(${index + 1}) Failed Step Error: ${error}"
            echo ""
        }

        def cucumberTestResult = (passedScenarios * 100) / totalScenarios

        return cucumberTestResult
    }
}