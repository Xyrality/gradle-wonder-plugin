package com.xyrality.gradle

import org.gradle.api.*
import org.gradle.api.tasks.*

class WOFramework extends WOProject {
	
	File frameworkOutputDir
	
	void apply(Project project) {
		super.apply(project)

		frameworkOutputDir = new File(project.buildDir, project.name + '.framework')

		configureEclipseProject(project)
		configureWOFrameworkTask(project)
		configureJarTaskResources(project)
	}

	def configureJarTaskResources(Project project) {
		project.with {
			jar {
				def infoPlistFile = new File(new File(frameworkOutputDir, 'Resources'), 'Info.plist')
	
				it.dependsOn project.woframework
				inputs.file(infoPlistFile)
	
				into('Resources') {
					from infoPlistFile
					from sourceSets.main.output.resourcesDir
				}
			}
		}
	}
		
	def configureWOFrameworkTask(Project project) {
		project.with {
			project.task('woframework', dependsOn: [project.classes, project.copyDependencies]) {
				description 'Build this framework as WebObjects framework structure'
	
				inputs.dir(sourceSets.main.output.classesDir)
				inputs.dir(sourceSets.main.output.resourcesDir)
				inputs.dir(sourceSets.webserver.output.resourcesDir)
				inputs.dir(dependencyLibDir)
				outputs.dir(frameworkOutputDir)
	
				doLast {
					sourceSets.main.output.classesDir.mkdirs()
					sourceSets.main.output.resourcesDir.mkdirs()
					sourceSets.webserver.output.resourcesDir.mkdirs()
					dependencyLibDir.mkdirs()
	
					ant.taskdef(name:'woframework', classname:'org.objectstyle.woproject.ant.WOFramework', classpath: configurations.woproject.asPath)
					ant.woframework(name:project.name, destDir:project.buildDir, javaVersion:project.targetCompatibility, cfBundleShortVersion: project.version, cfBundleVersion: project.version, principalClass: project.wonder.principalClass) {
						classes(dir:sourceSets.main.output.classesDir)
						resources(dir:sourceSets.main.output.resourcesDir)
						wsresources(dir:sourceSets.webserver.output.resourcesDir)
						lib(dir:dependencyLibDir)
					}
				}
			}
		}
	}
	
	def configureEclipseProject(Project project) {
		project.with {
			eclipse {
				eclipse.project {
					natures 'org.objectstyle.wolips.incrementalframeworknature'
					buildCommand 'org.objectstyle.wolips.incrementalbuilder'
				}
			}
		}
	}
}