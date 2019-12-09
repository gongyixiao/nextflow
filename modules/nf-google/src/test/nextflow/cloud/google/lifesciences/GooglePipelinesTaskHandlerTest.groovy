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
package nextflow.cloud.google.lifesciences.pipelines

import java.nio.file.Paths

import com.google.api.services.lifesciences.v2beta.model.Event
import com.google.api.services.lifesciences.v2beta.model.Metadata
import com.google.api.services.lifesciences.v2beta.model.Operation
import nextflow.Session
import nextflow.cloud.google.GoogleSpecification
import nextflow.cloud.google.pipelines.GooglePipelinesConfiguration
import nextflow.cloud.google.pipelines.GooglePipelinesExecutor
import nextflow.cloud.google.pipelines.GooglePipelinesHelper
import nextflow.cloud.google.pipelines.GooglePipelinesSubmitRequest
import nextflow.cloud.google.pipelines.GooglePipelinesTaskHandler
import nextflow.cloud.types.PriceModel
import nextflow.exception.ProcessUnrecoverableException
import nextflow.executor.Executor
import nextflow.processor.TaskConfig
import nextflow.processor.TaskId
import nextflow.processor.TaskProcessor
import nextflow.processor.TaskRun
import nextflow.processor.TaskStatus
import nextflow.script.BaseScript
import nextflow.script.ProcessConfig
import nextflow.util.CacheHelper
import spock.lang.Shared

class GooglePipelinesTaskHandlerTest extends GoogleSpecification {

    @Shared
    GooglePipelinesConfiguration pipeConfig = new GooglePipelinesConfiguration("testProject",["testZone"],["testRegion"])

    @Shared
    UUID uuid = new UUID(4,4)

    @Shared
    Session stubSession = Stub {
        getUniqueId() >> uuid
        getConfig() >> ["cloud" : ["instanceType": "instanceType"]]
    }

    GooglePipelinesExecutor stubExecutor = GroovyStub() {
        getSession() >> stubSession
        getHelper() >> GroovyMock(GooglePipelinesHelper)
    }

    TaskRun stubTaskRunner = GroovyStub {
        getName() >> "testName"
        getId() >> new TaskId(12345)
        getContainer() >> "testContainer"
        getScript() >> "echo testScript"
        getWorkDir() >> File.createTempDir().toPath()
        getHash() >> { CacheHelper.hasher('dummy').hash() }
    }

    def 'should throw an error if container is not specified'() {
        given:
        def noContainerTaskRunner = Stub(TaskRun) {
            getName() >> "noContainer"
        }

        when: 'handler is constructed'
        def handler = new GooglePipelinesTaskHandler(noContainerTaskRunner,stubExecutor,pipeConfig)

        then: 'we should get an error stating that container definition is missing'
        def error = thrown(ProcessUnrecoverableException)
        error.getMessage() == "No container image specified for process $noContainerTaskRunner.name -- Either specify the container to use in the process definition or with 'process.container' value in your config"
        !handler

    }

    def 'should construct correctly'() {
        when:
        def handler = new GooglePipelinesTaskHandler(stubTaskRunner, stubExecutor,pipeConfig)

        then:
        handler.task.container == "testContainer"
    }



    def 'should submit a task'() {
        given:
        def task = Mock(TaskRun)
        task.getName() >> 'foo'

        def handler = Spy(GooglePipelinesTaskHandler)
        handler.task = task

        def req = Mock(GooglePipelinesSubmitRequest)
        def operation = new Operation()

        when:
        handler.submit()

        then:
        1 * handler.createTaskWrapper() >> null
        1 * handler.createPipelineRequest() >> req
        1 * handler.submitPipeline(req) >> operation
        1 * handler.getPipelineIdFromOp(operation) >> '123'

        handler.operation == operation
        handler.pipelineId == '123'
        handler.status == TaskStatus.SUBMITTED

    }

    def 'should check submitPipeline' () {
        given:
        def helper = Mock(GooglePipelinesHelper)
        def executor = new GooglePipelinesExecutor(helper: helper)
        def handler = new GooglePipelinesTaskHandler(executor: executor, helper: helper)

        def operation = new Operation()
        def request = Mock(GooglePipelinesSubmitRequest)

        when:
        def op = handler.submitPipeline(request)
        then:
        1 * helper.submitPipeline(request) >> operation
        op == operation
    }

    def 'should create pipeline request' () {
        given:

        def helper = Mock(GooglePipelinesHelper)
        def executor = new GooglePipelinesExecutor(helper: helper)
        def config = Mock(GooglePipelinesConfiguration)
        def workDir = mockGsPath('gs://my-bucket/work/dir')

        def task = Mock(TaskRun)
        task.getName() >> 'foo'
        task.getWorkDir() >> workDir
        task.getHash() >> { CacheHelper.hasher('dummy').hash() }
        task.getContainer() >> 'my/image'
        task.getConfig() >> new TaskConfig(disk: '250 GB', machineType: 'n1-1234')

        def handler = new GooglePipelinesTaskHandler(
                pipelineConfiguration: config,
                executor: executor,
                task: task )

        when:
        def req = handler.createPipelineRequest()

        then:
        config.getProject() >> 'my-project'
        config.getZone() >> ['my-zone']
        config.getRegion() >> ['my-region']
        config.getPreemptible() >> true
        // check request object
        and:
        req.machineType == 'n1-1234'
        req.project == 'my-project'
        req.zone == ['my-zone']
        req.region == ['my-region']
        req.diskName == GooglePipelinesTaskHandler.DEFAULT_DISK_NAME
        req.diskSizeGb == 250
        req.preemptible
        req.taskName == "nf-bad893071e9130b866d43a4fcabb95b6"
        req.containerImage == 'my/image'
        req.fileCopyImage == GooglePipelinesTaskHandler.DEFAULT_COPY_IMAGE
        req.workDir.toUriString() == 'gs://my-bucket/work/dir'
        req.remoteTaskDir == 'gs://my-bucket/work/dir'
        req.localTaskDir == '/work/dir'
        req.sharedMount.getPath() == '/work/dir'
        req.sharedMount.getDisk() == GooglePipelinesTaskHandler.DEFAULT_DISK_NAME
        !req.sharedMount.getReadOnly()
    }

   
    
    def 'should check if it is running'(){
        given:
        // -- task
        def task = Mock(TaskRun)
        task.name >> 'google-task'
        task.getWorkDir() >> Paths.get('/work/dir')

        // -- executor
        def helper = Mock(GooglePipelinesHelper)
        def executor = new GooglePipelinesExecutor(helper: helper)

        def operation = new Operation()

        // -- handler
        def handler = Spy(GooglePipelinesTaskHandler)
        handler.executor = executor
        handler.task = task
        handler.operation = operation
        handler.helper = helper

        when:
        def result = handler.checkIfRunning()
        then:
        1 * handler.isSubmitted() >> false
        0 * handler.executor.helper.checkOperationStatus(_)
        handler.status == TaskStatus.NEW
        result == false

        when:
        result = handler.checkIfRunning()
        then:
        1 * handler.isSubmitted() >> true
        1 * handler.executor.helper.checkOperationStatus(_) >> { new Operation() }
        handler.status == TaskStatus.RUNNING
        result == true

        when:
        result = handler.checkIfRunning()
        then:
        1 * handler.isSubmitted() >> false
        0 * handler.executor.helper.checkOperationStatus(_)
        handler.status == TaskStatus.RUNNING
        result == false

    }


    def 'should check if it is complete'() {
        given:
        // -- task
        def task = Mock(TaskRun)
        task.name >> 'google-task'
        task.getWorkDir() >> Paths.get('/work/dir')

        // -- executor
        def helper = Mock(GooglePipelinesHelper)
        def executor = new GooglePipelinesExecutor(helper:helper)

        // -- handler
        def handler = Spy(GooglePipelinesTaskHandler)
        handler.executor = executor
        handler.task = task
        handler.helper = helper

        when:
        def isComplete = handler.checkIfCompleted()
        then:
        1 * handler.isRunning() >> false
        0 * helper.checkOperationStatus(_)
        !isComplete

        when:
        isComplete = handler.checkIfCompleted()
        then:
        1 * handler.isRunning() >> true
        1 * helper.checkOperationStatus(_)  >> { new Operation().setName("incomplete").setDone(false) }
        !isComplete

        when:
        isComplete = handler.checkIfCompleted()
        then:
        1 * handler.readExitFile() >> 0
        1 * handler.isRunning() >> true
        1 * helper.checkOperationStatus(_) >> { new Operation().setName("complete").setDone(true) }
        isComplete
    }

    def 'should get jobId from operation' () {
        given:
        def operation = new Operation().setName('projects/rare-lattice-222412/operations/16737869387120678662')
        def handler = [:] as GooglePipelinesTaskHandler
        expect:
        handler.getPipelineIdFromOp(operation) == '16737869387120678662'
    }

    def 'should get events from operation' () {
        given:
        def handler = [:] as GooglePipelinesTaskHandler

        when:
        def op = new Operation()
        def events = handler.getEventsFromOp(op)
        then:
        events == []

        when:
        def e1 = new Event().setDescription('foo').setTimestamp('2018-12-15T12:50:30.743109Z')
        op.setMetadata(new Metadata().setEvents([e1]))
        events = handler.getEventsFromOp(op)
        then:
        events == [e1]

        when:
        def e2 = new Event().setDescription('bar').setTimestamp('2018-12-15T12:52:30.743109Z')
        def e3 = new Event().setDescription('baz').setTimestamp('2018-12-15T12:55:30.743109Z')
        op.setMetadata(new Metadata().setEvents([e3, e2, e1]))
        events = handler.getEventsFromOp(op)
        then:
        events == [e2, e3]

    }

    def 'should create disk mount'() {
        given:
        def diskName = "testDisk"
        def mountPath = "testPath"
        def readOnly = true
        and:
        def handler = Spy(GooglePipelinesTaskHandler)

        when:
        def mount1 = handler.configureMount(diskName,mountPath,readOnly)
        then:
        with(mount1) {
            getDisk() == diskName
            getPath() == mountPath
            getReadOnly() == readOnly
        }

        when:
        def mount2 = handler.configureMount(diskName,mountPath)
        then:
        with(mount2) {
            getDisk() == diskName
            getPath() == mountPath
            !getReadOnly()
        }

    }

    def 'should create trace record'() {
        given:
        def exec = Mock(Executor) { getName() >> 'google-pipelines' }
        def processor = Mock(TaskProcessor)
        processor.getExecutor() >> exec
        processor.getName() >> 'foo'
        processor.getConfig() >> new ProcessConfig(Mock(BaseScript))
        def task = Mock(TaskRun)
        task.getProcessor() >> processor
        task.getConfig() >> Mock(TaskConfig) { getMachineType() >> 'm1.large' }
        and:
        def handler = Spy(GooglePipelinesTaskHandler)
        handler.task = task
        handler.pipelineId = 'xyz-123'
        handler.pipelineConfiguration = new GooglePipelinesConfiguration(zone: ['eu-east-1'], preemptible: true)

        when:
        def record = handler.getTraceRecord()
        then:
        record.get('native_id') == 'xyz-123'
        record.getExecutorName() == 'google-pipelines'
        record.machineInfo.type == 'm1.large'
        record.machineInfo.zone == 'eu-east-1'
        record.machineInfo.priceModel == PriceModel.spot
    }

}
