package com.xyrality.gradle

import org.gradle.api.*
import groovyx.net.http.RESTClient
import groovy.util.slurpersupport.GPathResult
import static groovyx.net.http.ContentType.URLENC
import static groovyx.net.http.ContentType.JSON
import org.gradle.api.tasks.bundling.*
import org.gradle.api.tasks.*
import org.apache.commons.io.FileUtils

buildscript {
	repositories {
		mavenCentral()
	}

	dependencies {
		classpath 'org.codehaus.groovy.modules.http-builder:http-builder:0.5.1'
		classpath 'commons-io:commons-io:2.4'
	}
}

class WOApplication extends WOProject {

	File applicationOutputDir
	String woaName
	String woaVersionedName

	void apply(Project project) {
		super.apply(project)
		
		def extraVersion = System.getProperty('extraVersion')
		if (extraVersion != null) {
			if (extraVersion != "") {
				project.version = project.version + '-' + System.getProperty('extraVersion')
			}
		} else {
			project.version = project.version + project.versionNameSuffix
		}

		woaName = project.name + '.woa'
		woaVersionedName = project.name + '-' + project.version + '.woa'
		applicationOutputDir = new File(project.buildDir, woaName)

		configureEclipseProject(project)
		configureWOApplicationTasks(project)
		
		project.afterEvaluate {
			configureDeployTasks(project)
		}

		project.build.dependsOn project.woApplicationTarGz
		project.test.dependsOn project.woapplication
	}

	def configureDeployTasks(Project project) {
		def deploymentServers = project.wonder.deploymentServers
		def deploymentPath = project.wonder.deploymentPath
		def deploymentSSHUser = project.wonder.deploymentSSHUser
		def deploymentMonitorBounceTasks = project.wonder.deploymentMonitorBounceTasks
		def deploymentSSHPort = project.wonder.deploymentSSHPort
		def deploymentSSHIgnoreHostKey = project.wonder.deploymentSSHIgnoreHostKey 
		
		if (!deploymentPath.endsWith('/')) {
			deploymentPath += '/'
		}
		
		if (deploymentServers.size() != 0) {
			if (deploymentPath.length() == 0 || deploymentSSHUser.length() == 0) {
				throw new InvalidUserDataException('Need to specify deploymentPath and deploymentSSHUser when specifying deploymentServers')
			}
			
			project.with {
				task('copyToServers') {
					// empty task, only to be used for dependencies
					description 'collection task to copy to all stage servers'
				}

				for (int i = 0; i < deploymentServers.size(); i++) {
					def deploymentServer = deploymentServers[i]
					def copyTaskName = 'copyToServer-' + deploymentServer
					def destinationPath = deploymentPath + woaVersionedName
					def taskDescription = 'copies the WOA binary to ' + deploymentServer
					def additionalSSHParameters = ''
					
					if (deploymentSSHIgnoreHostKey) {
						additionalSSHParameters = '-o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no'
					} 
					
					task(copyTaskName, type: Exec, dependsOn: woapplication) {
						description = taskDescription
						workingDir buildDir
						def sshCommand = String.format('tar -C "%s" -zcf - ./ | ssh %s -p %d %s@%s "mkdir -p %s && tar -C %s -zxf -"', woaName, additionalSSHParameters, deploymentSSHPort, deploymentSSHUser, deploymentServer, destinationPath, destinationPath)
						commandLine 'bash', '-c', sshCommand
					}
					project.copyToServers.dependsOn(copyTaskName)
				}
			}
	
			if (deploymentMonitorBounceTasks.size() > 0) {
				project.with {
					task('deployToServers', dependsOn: 'copyToServers') {
						// empty task, only to be used for dependencies
						description 'collection task to deploy to all stage servers'
					}

					for (keyValuePair in deploymentMonitorBounceTasks) {
						def serverName = keyValuePair.key
						def appNameArray = keyValuePair.value instanceof String ? [keyValuePair.value] : keyValuePair.value
						def unixPath = deploymentPath + woaVersionedName + '/' + project.name
						def copyTaskName = 'copyToServer-' + serverName

						for (appName in appNameArray) {
							def taskName = 'deployToServer-' + serverName + '/' + appName;
							task(taskName, dependsOn: copyTaskName) {
								description 'deploys the binary to ' + serverName + ' as application ' + appName
								doLast {
									bounceWOApplication(logger, String.format(project.wonder.deploymentMonitorURLPattern, serverName), unixPath, appName)
								}
							}

							project.deployToServers.dependsOn(taskName)
						}
					}
				}
			}
		}
	}

	def bounceWOApplication(logger, monitorURL, unixPath, appName) {
		logger.info "JavaMonitor >> Updating JavaMonitor at $monitorURL"
		logger.info "JavaMonitor >> Executable path is $unixPath"
		logger.info "JavaMonitor >> Application name is $appName"

		def monitor = new RESTClient(monitorURL)

		logger.info "JavaMonitor >> Setting executable path to $unixPath..."
		def responseSet = monitor.put(path: 'ra/mApplications/' + appName + '.json', contentType: JSON, requestContentType: URLENC, body: "{unixPath:'$unixPath'}")
		assert responseSet.status == 200
		logger.info 'JavaMonitor >> Set executable path SUCCESSFUL'

		logger.info 'JavaMonitor >> Bouncing application...'
		def responseBounce = monitor.get(path: 'admin/bounce', query: [type:'app', name:appName])
		assert responseBounce.status == 200
		logger.info 'JavaMonitor >> Bounce command SUCCESSFUL'

		logger.info 'JavaMonitor >> Disabling autorecover...'
		def responseAutorecoverOff = monitor.get(path: 'admin/turnAutoRecoverOff', query: [type:'app', name:appName])
		assert responseAutorecoverOff.status == 200
		logger.info 'JavaMonitor >> Autorecover off command SUCCESSFUL'
	}

	def configureWOApplicationTasks(Project project) {
		project.with {
			task('woapplication', dependsOn: [classes, copyDependencies]) {
				description 'Build this framework as WebObjects application'

				inputs.dir(sourceSets.main.output.classesDir)
				inputs.dir(sourceSets.main.output.resourcesDir)
				inputs.dir(sourceSets.webserver.output.resourcesDir)
				inputs.dir(dependencyLibDir)
				inputs.dir(dependencyFrameworksDir)
				outputs.dir(applicationOutputDir)

				doLast {
					if (project.wonder.applicationClass.length() == 0) {
						throw new InvalidUserDataException('woapplication builds need to define an applicationClass property on the project level!')
					}

					sourceSets.main.output.classesDir.mkdirs()
					sourceSets.main.output.resourcesDir.mkdirs()
					sourceSets.webserver.output.resourcesDir.mkdirs()
					dependencyLibDir.mkdirs()
					dependencyFrameworksDir.mkdirs()

					ant.taskdef(name:'woapplication', classname:'org.objectstyle.woproject.ant.WOApplication', classpath: configurations.woproject.asPath)
					ant.woapplication(name:project.name, destDir:project.buildDir, javaVersion:project.targetCompatibility, principalClass:project.wonder.applicationClass, cfBundleVersion: project.version, cfBundleShortVersion: project.version, frameworksBaseURL: "/WebObjects/${project.name}.woa/Contents/Frameworks") {
						classes(dir:sourceSets.main.output.classesDir)
						resources(dir:sourceSets.main.output.resourcesDir)
						wsresources(dir:sourceSets.webserver.output.resourcesDir)
						lib(dir:dependencyLibDir)
						frameworks(dir:dependencyFrameworksDir, embed:'true') {
							include(name:'**/*.framework')
						}
					}
				}
			}

			task('woApplicationTarGz', type: Tar, dependsOn: [woapplication]) {
				description 'Build a .tar.gz file containing the complete application'

				extension = 'tar.gz'
				baseName = project.name
				compression = Compression.GZIP

				into(woaVersionedName)
				from(applicationOutputDir)
			}
		}
	}

	def configureEclipseProject(Project project) {
		project.eclipse {
			project.eclipse.project {
				natures 'org.objectstyle.wolips.incrementalapplicationnature'
				buildCommand 'org.objectstyle.wolips.incrementalbuilder'
			}
		}
	}
}
