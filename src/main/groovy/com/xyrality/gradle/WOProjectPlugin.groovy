package com.xyrality.gradle

import org.gradle.api.*

import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.CopyMoveHelper.CopyOptions
import java.nio.file.StandardCopyOption
import java.util.jar.*
import java.util.zip.*

import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils

import groovy.util.Node

import org.gradle.api.specs.Specs
import org.gradle.api.tasks.*
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.MavenPlugin
import org.gradle.api.tasks.bundling.*
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.testing.jacoco.plugins.JacocoPlugin


class WOProject implements Plugin<Project> {
	void apply(Project project) {
		project.getPlugins().apply(BasePlugin.class)
		project.getPlugins().apply(JavaPlugin.class)
		project.getPlugins().apply(MavenPlugin.class)
		project.getPlugins().apply(EclipsePlugin.class)
		
		project.extensions.create('wonder', WOProjectPluginExtension)

		if (!project.hasProperty('version') || (project.version == 'unspecified')) {
			throw new InvalidUserDataException('Subprojects need to define their versions')
		}

		project.ext {
			versionNameSuffix = (new Date()).format('-yyyyMMdd-HHmm')
			dependencyLibDir = new File(project.buildDir, 'dependency-libs')
			dependencyFrameworksDir = new File(project.buildDir, 'dependency-frameworks')
		}

		project.sourceCompatibility = '1.6'
		project.targetCompatibility = '1.6'

		configureFluffyBunnyProjectStructure(project)
		configureEclipseClasspath(project)
		configureWOProjectDependency(project)
		configureDependenciesTask(project)
		configureResourceTasks(project)
		configureJarTasks(project)
		installRepositories(project)
		addWODependencies(project)
		addTestDependencies(project)
	}

	def configureWOProjectDependency(Project project) {
		project.with {
			def woProjectURL = 'http://webobjects.mdimension.com/hudson/job/WOLips36Stable/lastSuccessfulBuild/artifact/woproject.jar'
			def woProjectLibs = new File(project.projectDir, 'woproject-libs')
			woProjectLibs.mkdirs()
			
			def woProjectLocalPath = new File(woProjectLibs, 'woproject.jar')
			if (!woProjectLocalPath.exists()) {
				logger.warn 'Missing woproject.jar, downloading to ' + woProjectLocalPath.path + '...'
				
				def outputStream = woProjectLocalPath.newOutputStream()
				outputStream << new URL(woProjectURL).openStream()
				outputStream.close()
			} 
			
			configurations { woproject }

			dependencies {
				// usually woproject group:'org.objectstyle.woproject.ant', name:'woproject-ant-tasks', version:'2.0.16'
				woproject files(woProjectLocalPath)
			}
		}
	}

	def configureFluffyBunnyProjectStructure(Project project) {
		project.with {
			sourceSets {
				main {
					java { srcDirs = ['Sources']}
					resources { srcDirs = ['Resources']}
				}
				
				test {
					java { srcDirs = ['TestSources']}
					resources { srcDirs = ['TestResources']}
					runtimeClasspath = sourceSets.main.output + files(output.resourcesDir) + files(output.classesDir) + configurations.testRuntime
				}

				integrationTest {
					java { srcDirs = ['TestTransferSources']}
					runtimeClasspath = sourceSets.main.output + files(output.resourcesDir) + files(output.classesDir) + configurations.testRuntime
				}

				webserver {
					resources { srcDirs = ['WebServerResources']}
				}

				components {
					resources { srcDirs = ['Components']}
				}
			}
		}
	}

	def configureDependenciesTask(Project project) {
		project.with {
			task('copyDependencies', dependsOn: classes) {
				description 'copies all JARs on which this build depends on to the dependency-libs directory, converting frameworks in the process'

				outputs.dir(dependencyLibDir)
				outputs.dir(dependencyFrameworksDir)

				doLast {
					dependencyLibDir.mkdirs()
					dependencyFrameworksDir.mkdirs()

					configurations.runtime.resolvedConfiguration.getFiles(Specs.satisfyAll()).each {
						def basename = it.toPath().getFileName().toString()
						def jarFile = new JarFile(it)
						def jarEntry = jarFile.getJarEntry("Resources/Info.plist")
						if (jarEntry != null) {
							def xmlContent = new Scanner(jarFile.getInputStream(jarEntry)).useDelimiter("\\A").next()
							def parser = new XmlParser(false, false, true)
							parser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
							def plist = parser.parseText(xmlContent)

							def curKey = ""
							def valueMap = [:]
							plist.dict[0].children().each {
								if (it instanceof Node) {
									switch (it.name()) {
										case "key":
											curKey = it.value()[0]
											break
										case "string":
											valueMap.put(curKey, it.value()[0])
											break
										case "true":
											valueMap.put(curKey, true)
											break
										case "false":
											valueMap.put(curKey, false)
											break
										case "array":
											valueMap.put(curKey, it.children().collect { x -> x.value()[0] })
											break
									}
								}
							}

							if (valueMap.get("CFBundlePackageType") == "FMWK") {
								def bundleName = valueMap.get("CFBundleExecutable")
								def outputDir = new File(dependencyFrameworksDir, bundleName + ".framework")
								def jarsDir = new File(new File(outputDir, "Resources"), "Java")
								def jarOutputFile = new File(jarsDir, bundleName.toLowerCase() + ".jar")

								logger.info(bundleName + " is a framework, will convert to .framework folder " + basename)
								jarsDir.mkdirs()
								outputDir.mkdirs()

								def jarOutput = new ZipOutputStream(new FileOutputStream(jarOutputFile))
								for (JarEntry entry : jarFile.entries()) {
									def entryFileName = entry.getName()

									if ((entryFileName.startsWith("Resources") && !entryFileName.contains("/WebServerResources/")) || entryFileName.startsWith("WebServerResources")) {
										File outputFile = new File(outputDir, entryFileName)
										if (!entry.isDirectory()) {
											outputFile.parentFile.mkdirs()
											InputStream inputStream = jarFile.getInputStream(entry)
											FileOutputStream outputStream = new FileOutputStream(outputFile)
											IOUtils.copy(inputStream, outputStream)
											IOUtils.closeQuietly(inputStream)
											IOUtils.closeQuietly(outputStream)
										}
									} else if (!entryFileName.startsWith("META-INF")) {
										InputStream inputStream = jarFile.getInputStream(entry)
										jarOutput.putNextEntry(new ZipEntry(entry))
										IOUtils.copy(inputStream, jarOutput)
									}
								}
								jarOutput.close()

								return
							}
						}

						def outputFile = new File(dependencyLibDir, basename)
						logger.info(basename + " is no framework, will copy to libs folder")
						Files.copy(it.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
					}
				}
			}
		}
	}

	def configureResourceTasks(Project project) {
		project.with {
			task('copyComponents', type: Copy){
				description 'copy WOComponents to resources and flatten'

				sourceSets.components.resources.srcDirs.each{from fileTree(it)}
				into(sourceSets.main.output.resourcesDir)
				includeEmptyDirs=false

				eachFile {details ->
					details.path = details.path.replaceAll(/^.*\/([^\/]*\.wo|[^\/]*\.wo\/[^\n]*|[^\/]*.api)$/, {"${it[1]}"})
				}

				processResources.dependsOn it
			}

			task('copyWebServerResources', type: Copy) {
				description 'copy web server resources to WebServerResources'

				from sourceSets.webserver.resources
				into sourceSets.webserver.output.resourcesDir

				processResources.dependsOn it
			}
		}
	}

	def configureJarTasks(Project project) {
		project.with {
			jar {
				from(sourceSets.webserver.resources.srcDirs) { into('WebServerResources') }
			}

			task('testJar', type: Jar, dependsOn: testClasses) {
				classifier = 'tests'
				from sourceSets.test.output

				assemble.dependsOn it
			}

			task('sourcesJar', type: Jar, dependsOn: classes) {
				classifier = 'sources'
				from sourceSets.main.allSource
			}

			configurations { tests }

			artifacts {
				tests testJar
				archives sourcesJar
			}
		}
	}

	def addWODependencies(Project project) {
		project.dependencies {
			def wonderVersion = project.wonder.wonderVersion
			def webobjectsVersion = project.wonder.webobjectsVersion

			compile group: 'wonder.core', name: 'ERExtensions', version: wonderVersion
			compile group: 'wonder.core', name: 'ERFoundation', version: '1.0'
			compile group: 'wonder.core', name: 'ERWebObjects', version: '1.0'
			compile group: 'wonder.core', name: 'ERPrototypes', version: wonderVersion
			compile group: 'com.webobjects', name: 'JavaEOAccess', version: webobjectsVersion
			compile group: 'com.webobjects', name: 'JavaEOControl', version: webobjectsVersion
			compile group: 'com.webobjects', name: 'JavaFoundation', version: webobjectsVersion
			compile group: 'com.webobjects', name: 'JavaWebObjects', version: webobjectsVersion
			compile group: 'com.webobjects', name: 'JavaJDBCAdaptor', version: webobjectsVersion
			compile group: 'wonder.plugins', name: 'PostgresqlPlugIn', version: wonderVersion
			compile group: 'postgresql', name: 'postgresql', version: '9.0-801.jdbc4'
			compile group: 'org.eclipse.jdt', name:'org.eclipse.jdt.annotation', version:'2.0.0.v20140415-1436'
		}
	}

	def addTestDependencies(Project project) {
		def wonderVersion = project.wonder.wonderVersion
		def webobjectsVersion = project.wonder.webobjectsVersion

		project.dependencies {
			testCompile 'org.mockito:mockito-all:1.10.19'
			testCompile group: 'com.wounit', name: 'wounit', version: '1.2.1'
			testCompile group: 'junit', name: 'junit', version: '4.11'
			testCompile group: 'wonder.eoadaptors', name: 'JavaMemoryAdaptor', version: wonderVersion
		}
	}

	def installRepositories(Project project) {
		project.repositories {
			mavenCentral()

			maven { url 'http://nexus.xyrality.net/content/repositories/thirdparty/' }
			maven { url 'http://maven.wocommunity.org/content/groups/public' }
			maven { url 'http://maven.wocommunity.org/content/groups/public-snapshots' }
			maven { url 'https://repo.eclipse.org/content/repositories/releases/' }
		}
	}

	def configureEclipseClasspath(Project project) {
		project.with {
			eclipse {
				classpath {
					downloadJavadoc = true
					file {
						whenMerged{ classpath ->
							def erExtensions = classpath.entries.findAll{entry -> entry.path.contains('ERExtensions')}

							classpath.entries.removeAll(erExtensions)
							for (classpathEntry in erExtensions) {
								classpath.entries.add(0, classpathEntry)
							}
						}
					}
				}
			}

			eclipseClasspath.doLast {
				eclipse.classpath.sourceSets.main.allSource.each { it.mkdirs() }
				eclipse.classpath.sourceSets.main.output.each { it.mkdirs() }
				eclipse.classpath.sourceSets.test.output.each { it.mkdirs() }
				eclipse.classpath.sourceSets.integrationTest.output.each { it.mkdirs() }
			}
		}
	}
}

class WOProjectPluginExtension {
	def webobjectsVersion = '5.4.3'
	def wonderVersion = '6.1.2'
	def deploymentServers = []
	def deploymentPath = ''
	def deploymentSSHPort = 22
	def deploymentSSHUser = ''
	def deploymentSSHIgnoreHostKey = false
	def deploymentMonitorBounceTasks = [:]
	def deploymentMonitorURLPattern = 'http://%s:1086/cgi-bin/WebObjects/JavaMonitor.woa/'
	def applicationClass = ''
	def principalClass = ''
}
