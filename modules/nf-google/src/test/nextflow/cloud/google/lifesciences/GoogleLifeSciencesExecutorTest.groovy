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
package nextflow.cloud.google.lifesciences

import java.nio.file.Path
import java.nio.file.Paths

import nextflow.Session
import nextflow.cloud.google.GoogleSpecification
import nextflow.exception.AbortOperationException
import spock.lang.Shared

class GoogleLifeSciencesExecutorTest extends GoogleSpecification {

    @Shared
    def validZoneConfig = [
            "google" : [
            "project" : "testProject",
            "zone" : "testZone1,testZone2"
            ]
    ]

    @Shared
    def validRegionConfig = [
            "google" : [
                    "project" : "testProject",
                    "region" : "testRegion1,testRegion2"
            ]
    ]

    @Shared
    Map ENV = [GOOGLE_APPLICATION_CREDENTIALS: '/some/file.json']

    def 'should abort operation when the workdir is not a CloudStoragePath'() {
        given:
        def session = Stub(Session)
        session.workDir = Stub(Path)
        and:
        def executor = new GoogleLifeSciencesExecutor(env: ENV, session: session)

        when:
        executor.register()

        then:
        def error = thrown(AbortOperationException)
        error.getMessage().startsWith("When using `google-lifesciences` executor a Google Storage bucket must be specified as a working directory")
    }

    def 'should abort operation when project is not specified'() {
        given:
        def session = Stub(Session)
        def path = mockGsPath('gs://work/dir')
        session.workDir >> path
        session.bucketDir >> null
        session.binDir >> null
        session.config >> [
                "google" : [
                        "zone" : "testZone"
                ]
        ]
        and:
        def executor = new GoogleLifeSciencesExecutor(env: ENV, session: session)

        when:
        executor.register()

        then:
        def error = thrown(AbortOperationException)
        error.getMessage().startsWith("Missing Google project Id")
    }

    def 'should abort operation when neither zone or region are specified'() {
        given:
        def session = Stub(Session)
        def path = mockGsPath('gs://work/dir')
        session.workDir >> path
        session.bucketDir >> null
        session.binDir >> null
        session.config >> [
                "google" : [
                        "project" : "testproject"
                ]
        ]
        and:
        def executor = new GoogleLifeSciencesExecutor(env: ENV, session: session)

        when:
        executor.register()

        then:
        def error = thrown(AbortOperationException)
        error.getMessage().contains("Missing configuration value 'google.zone' or 'google.region'")
    }


    def 'should abort operation when both zone and region are specified'() {
        given:
        def session = Stub(Session)
        def path = mockGsPath('gs://work/dir')
        session.workDir >> path
        session.bucketDir >> null
        session.binDir >> null
        session.config >> [
                "google" : [
                        "project" : "testproject",
                        "zone" : "testZone",
                        "region" : "testRegion"
                ]
        ]
        and:
        def executor = new GoogleLifeSciencesExecutor(env: ENV, session: session)

        when:
        executor.register()

        then:
        def error = thrown(AbortOperationException)
        error.getMessage().contains("You can't specify both 'google.zone' and 'google.region' configuration parameters -- Please remove one of them from your configuration")
    }


    def 'should register successfully with zone'()  {
        given:
        def session = Mock(Session)
        def path = mockGsPath('gs://foo/work/dir')
        session.bucketDir >> path
        session.binDir >> null
        session.config >> validZoneConfig
        and:
        def executor = new GoogleLifeSciencesExecutor(env: ENV, session: session)

        when:
        executor.register()

        then:
        executor.config.project == validZoneConfig.google?.project
        executor.config.zones == validZoneConfig.google?.zone?.split(",")?.toList()
    }

    def 'should register successfully with region'()  {
        given:
        def session = Mock(Session)
        def path = mockGsPath('gs://foo/bar')
        session.bucketDir >> path
        session.config >> validRegionConfig
        session.binDir >> null
        and:
        def executor = new GoogleLifeSciencesExecutor(env: ENV, session: session)

        when:
        executor.register()

        then:
        executor.config.project == validRegionConfig.google?.project
        executor.config.regions == validRegionConfig.google?.region?.split(",")?.toList()
    }

    def 'should stop on missing credentials' () {
        given:
        def session = Mock(Session)
        def path = mockGsPath('gs://foo/bar')
        session.bucketDir >> path
        session.binDir >> null
        and:
        def executor = new GoogleLifeSciencesExecutor(env: [:], session: session)

        when:
        executor.register()
        then:
        def err = thrown(AbortOperationException)
        err.message.startsWith('Missing Google credentials')
    }

    def 'should stop on missing bucket' () {
        given:
        def session = Mock(Session)
        def path = Paths.get('/local/dir')
        session.bucketDir >> path
        session.binDir >> null
        and:
        def executor = new GoogleLifeSciencesExecutor(env: ENV, session: session)

        when:
        executor.register()
        then:
        def err = thrown(AbortOperationException)
        err.message.startsWith('Executor `google-lifesciences` requires a Google Storage bucket to be specified as a working directory')
    }

    def 'should be containerNative'() {
        when:
        def executor = new GoogleLifeSciencesExecutor()
        then:
        executor.isContainerNative()
    }
}
