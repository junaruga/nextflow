/*
 * Copyright 2013-2019, Centre for Genomic Regulation (CRG)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.processor

import java.nio.file.NoSuchFileException
import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.executor.Executor
import nextflow.executor.ExecutorFactory
import nextflow.script.BaseScript
import nextflow.script.ScriptBinding
import nextflow.script.TaskBody

/**
 *  Factory class for {@TaskProcessor} instances
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class ProcessFactory {

    private Session session

    private Map config

    private BaseScript owner

    private ExecutorFactory executorFactory

    /* only for test -- do not use */
    protected ProcessFactory() { }

    ProcessFactory( BaseScript ownerScript, Session session ) {
        this.owner = ownerScript
        this.session = session
        this.config = session.config
        this.executorFactory = session.executorFactory
    }

    Session getSession() { session }

    /**
     * Create a new task processor and initialise with the given parameters
     *
     * @param name The processor name
     * @param executor The executor object
     * @param session The session object
     * @param script The owner script
     * @param config The process configuration
     * @param taskBody The process task body
     * @return An instance of {@link TaskProcessor}
     */
    protected TaskProcessor newTaskProcessor( String name, Executor executor, ProcessConfig config, TaskBody taskBody ) {
        new TaskProcessor(name, executor, session, owner, config, taskBody)
    }

    /**
     * Create a task processor
     *
     * @param name
     *      The name of the process as defined in the script
     * @param body
     *      The process declarations provided by the user
     * @param options
     *      A map representing the named parameter specified after the process name eg:
     *          `process foo(bar: 'x') {  }`
     *      (not used)
     * @return
     *      The {@code Processor} instance
     */
    TaskProcessor createProcessor( String name, Closure body, Map options = null ) {
        assert body
        assert config.process instanceof Map

        /*
         * check if exists 'attributes' defined in the 'process' scope for this process, e.g.
         *
         * process.$name.attribute1 = xxx
         * process.$name.attribute2 = yyy
         *
         * NOTE: THIS HAS BEEN DEPRECATED AND WILL BE REMOVED
         */
        Map legacySettings = null
        if( config.process['$'+name] instanceof Map ) {
            legacySettings = (Map)config.process['$'+name]
            log.warn "Process configuration syntax \$processName has been deprecated -- Replace `process.\$$name = <value>` with a process selector"
        }

        // -- the config object
        final processConfig = new ProcessConfig(owner).setProcessName(name)

        // Invoke the code block which will return the script closure to the executed.
        // As side effect will set all the property declarations in the 'taskConfig' object.
        processConfig.throwExceptionOnMissingProperty(true)
        final copy = (Closure)body.clone()
        copy.setResolveStrategy(Closure.DELEGATE_FIRST)
        copy.setDelegate(processConfig)
        final script = copy.call() as TaskBody
        processConfig.throwExceptionOnMissingProperty(false)
        if ( !script )
            throw new IllegalArgumentException("Missing script in the specified process block -- make sure it terminates with the script string to be executed")

        // -- apply settings from config file to process config
        applyConfig(name, processConfig, legacySettings)

        // -- get the executor for the given process config
        final execObj = executorFactory.createExecutor(name, processConfig, script, session)

        // -- create processor class
        newTaskProcessor( name, execObj, processConfig, script )
    }


    private void applyConfig(String name, ProcessConfig processConfig, Map legacySettings=null) {
        // -- Apply the directives defined in the config object using the`withLabel:` syntax
        final processLabels = processConfig.getLabels() ?: ['']
        for( String lbl : processLabels ) {
            processConfig.applyConfigForLabel(config.process as Map, "withLabel:", lbl)
        }

        // -- apply setting for name
        processConfig.applyConfigForLabel(config.process as Map, "withName:", name)

        // -- Apply process specific setting defined using `process.$name` syntax
        //    NOTE: this is deprecated and will be removed
        if( legacySettings ) {
            processConfig.applyConfigSettings(legacySettings)
        }

        // -- Apply defaults
        processConfig.applyConfigDefaults( config.process as Map )

        // -- check for conflicting settings
        if( processConfig.scratch && processConfig.stageInMode == 'rellink' ) {
            log.warn("Directives `scratch` and `stageInMode=rellink` conflict each other -- Enforcing default stageInMode for process `$name`")
            processConfig.remove('stageInMode')
        }
    }

    void defineProcess(String name, Closure body) {
        // the config object
        final processConfig = new ProcessConfig(owner).setProcessName(name)

        // Invoke the code block which will return the script closure to the executed.
        // As side effect will set all the property declarations in the 'taskConfig' object.
        processConfig.throwExceptionOnMissingProperty(true)
        final copy = (Closure)body.clone()
        copy.setResolveStrategy(Closure.DELEGATE_FIRST)
        copy.setDelegate(processConfig)
        final script = copy.call() as TaskBody
        processConfig.throwExceptionOnMissingProperty(false)
        if ( !script )
            throw new IllegalArgumentException("Missing script in the specified process block -- make sure it terminates with the script string to be executed")

        // apply config settings to the process
        applyConfig(name, processConfig)

        final process = new ProcessDef(owner, name, this, processConfig.clone(), script)
        session.library.register(process)
    }

    void require(def path, Map params) {
        assert path
        final module = resolveModulePath(path)
        try {
            final binding = new ScriptBinding(module: true)
            binding.setParams(params)
            session.getScriptParser().parse(module,binding).run()
        }
        catch( NoSuchFileException e ) {
            throw new IllegalArgumentException("Module file does not exists: $module")
        }
        catch( Exception e ) {
            throw new IllegalArgumentException("Unable to load module file: $module", e)
        }
    }

    private Path resolveModulePath(def path ) {
        def result = path as Path
        if( result.fileSystem != session.baseDir.fileSystem )
            throw new IllegalArgumentException("Remote module files are not allowed: ${result.toUriString()}")
        if( !result.isAbsolute() )
            result = session.baseDir.resolve(result)
        return result
    }

}
