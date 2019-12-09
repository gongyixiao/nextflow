/*
 * Copyright 2019, Google Inc
 * Copyright 2018, WuxiNextcode
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

package nextflow.cloud.google.lifesciences

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.exception.AbortOperationException
import nextflow.executor.Executor
import nextflow.executor.SupportedScriptTypes
import nextflow.extension.FilesEx
import nextflow.processor.TaskHandler
import nextflow.processor.TaskMonitor
import nextflow.processor.TaskPollingMonitor
import nextflow.processor.TaskRun
import nextflow.script.ScriptType
import nextflow.util.Duration
import nextflow.util.ServiceName
/**
 * Google Pipelines Executor.
 *
 * https://cloud.google.com/genomics/
 *
 * @author Ólafur Haukur Flygenring <olafurh@wuxinextcode.com>
 */
@Slf4j
@CompileStatic
@ServiceName('google-lifesciences')
@SupportedScriptTypes(ScriptType.SCRIPTLET)
class GoogleLifeSciencesExecutor extends Executor {

    private GoogleLifeSciencesConfig config

    private GoogleLifeSciencesHelper helper

    Map env = new HashMap(System.getenv())

    @Override
    final boolean isContainerNative() {
        return true
    }

    @Override
    final Path getWorkDir() {
        session.bucketDir ?: session.workDir
    }

    @Override
    protected void register() {
        super.register()
        initialize0()
        log.debug "[GLS] Google LifeSciences Configuration: '$config'"
    }

    @Override
    protected TaskMonitor createTaskMonitor() {
        TaskPollingMonitor.create(session, name, 1000, Duration.of('10 sec'))
    }

    @Override
    TaskHandler createTaskHandler(TaskRun task) {
        return new GoogleLifeSciencesTaskHandler(task, this)
    }

    protected GoogleLifeSciencesHelper getHelper() { return helper }

    protected GoogleLifeSciencesConfig getConfig() { return config }

    private void initialize0() {

        //Make sure that the workdir is a GS Bucket
        if ( getWorkDir()?.scheme != 'gs' ) {
            session.abort()
            throw new AbortOperationException("When using `google-lifesciences` executor a Google Storage bucket must be specified as a working directory -- Add the option `-w gs://<your-bucket/path>` to your run command line or specify a workDir in your config file")
        }

        if( !env.get('GOOGLE_APPLICATION_CREDENTIALS') )
            throw new AbortOperationException('Missing Google credentials -- make sure your environment defines the GOOGLE_APPLICATION_CREDENTIALS environment variable')

        config = GoogleLifeSciencesConfig.fromSession(session)

        if( session.binDir && !config.disableBinDir ) {
            final cloudPath = getTempDir()
            log.info "Uploading local `bin` scripts folder to ${cloudPath.toUriString()}/bin"
            config.remoteBinDir = FilesEx.copyTo(session.binDir, cloudPath)
        }

        log.debug "Google Life Science config=$config"
        helper = new GoogleLifeSciencesHelper().init()
    }


}
