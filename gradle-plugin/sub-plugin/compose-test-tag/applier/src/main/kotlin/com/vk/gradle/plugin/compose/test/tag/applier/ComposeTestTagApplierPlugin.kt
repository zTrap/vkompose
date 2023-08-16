package com.vk.gradle.plugin.compose.test.tag.applier

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class ComposeTestTagApplierPlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        target.extensions.create("composeTestTagApplier", ComposeTestTagApplierExtension::class.java)

        target.applyRuntimeDependency()
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project

        val extension =
            project.extensions.findByType(ComposeTestTagApplierExtension::class.java)
                ?: project.extensions.create(
                    "composeTestTagApplier",
                    ComposeTestTagApplierExtension::class.java
                )

        return project.provider {
            listOf(
                SubpluginOption(
                    "enabled",
                    extension.isEnabled.toString()
                ),
            )
        }
    }

    override fun getCompilerPluginId(): String = "com.vk.compose-test-tag-applier.compiler-plugin"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "com.vk.compose-test-tag-applier",
        artifactId = "compiler-plugin",
        version = "0.1"
    )


    private fun Project.applyRuntimeDependency() {
        dependencies {
            add("implementation", "com.vk.compose-test-tag-applier:compiler-runtime:0.1")
        }
    }

}