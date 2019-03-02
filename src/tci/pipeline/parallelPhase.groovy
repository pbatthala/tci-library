package tci.pipeline

import groovy.time.TimeCategory;

class parallelPhase implements Serializable {

    class subJob implements Serializable {

        String jobName
        def parameters
        boolean propagate
        boolean wait
        int retry

        subJob(String jobName, def parameters, boolean propagate, boolean wait, int retry ) {
            this.jobName = jobName
            this.parameters = parameters
            this.propagate = propagate
            this.wait = wait
            this.retry = retry
        }
    }

    class subRemoteJob implements Serializable {

        String jobName
        String remoteJenkinsName
        def parameters
        boolean abortTriggeredJob
        boolean useCrumbCache
        boolean useJobInfoCache
        int pollInterval
        int retry

        subRemoteJob(String jobName, String remoteJenkinsName, def parameters, boolean abortTriggeredJob, boolean useCrumbCache, boolean useJobInfoCache, int pollInterval, int retry ) {
            this.jobName = jobName
            this.remoteJenkinsName = remoteJenkinsName
            this.parameters = parameters
            this.abortTriggeredJob = abortTriggeredJob
            this.useCrumbCache = useCrumbCache
            this.useJobInfoCache = useJobInfoCache
            this.pollInterval = pollInterval
            this.retry = retry
        }
    }

    class stepsSequence implements Serializable {

        String sequenceName
        def sequence
        boolean propagate
        boolean wait
        int retry

        stepsSequence(String sequenceName, def sequence, boolean propagate, int retry ) {
            this.sequenceName = sequenceName
            this.sequence = sequence
            this.propagate = propagate
            this.retry = retry
        }
    }

    def script
    def name
    def jobs = []
    def remoteJobs = []
    def stepsSequences = []
    boolean failFast = false

    parallelPhase(script, String name = "TCI parallel", boolean failFast = false) {
        this.script = script
        this.name = name
        this.failFast = failFast
    }

    void addSubJob(Map config) {
        if (config == null) {
            config = [:]
        }
        if (config.job == null) {
            script.tciLogger.info ("[ERROR] you must provive a job name to run!!!")
            throw Exception
        }
        if (config.propagate == null) {
            config.propagate = true
        }
        if (config.parameters == null) {
            config.parameters = null
        }
        if (config.wait == null) {
            config.wait = true
        }
        if (config.retry == null) {
            config.retry = 1
        }

        def job = new subJob(config.job, config.parameters, config.propagate, config.wait, config.retry)
        jobs << job
    }

    void addRemoteSubJob(Map config) {
        if (config == null) {
            config = [:]
        }
        if (config.job == null) {
            script.tciLogger.info ("[ERROR] you must provive a job name to run!!!")
            throw Exception
        }
        if (config.remoteJenkinsName == null) {
            script.tciLogger.info ("[ERROR] you must provive the remote Jenkins server name (remoteJenkinsName) name to run!!!")
            throw Exception
        }
        if (config.abortTriggeredJob == null) {
            config.abortTriggeredJob = true
        }
        if (config.useCrumbCache == null) {
            config.useCrumbCache = true
        }
        if (config.useJobInfoCache == null) {
            config.useJobInfoCache = true
        }
        if (config.parameters == null) {
            config.parameters = null
        }
        if (config.wait == null) {
            config.wait = true
        }
        if (config.pollInterval == null) {
            config.pollInterval = 30
        }

        def remoteJob = new subRemoteJob(config.job, config.remoteJenkinsName, config.parameters, config.abortTriggeredJob, config.useCrumbCache, config.useJobInfoCache, config.pollInterval, config.retry)
        remoteJobs << remoteJob
    }

    void addStepsSequence(Map config) {
        if (config == null) {
            config = [:]
        }
        if (config.sequence == null) {
            script.tciLogger.info ("[ERROR] you must provive a block of steps sequence to run!!!")
            throw Exception
        }
        if (config.name == null) {
            config.name = "Steps sequence"
        }
        if (config.propagate == null) {
            config.propagate = true
        }
        if (config.retry == null) {
            config.retry = 1
        }

        def stepsSequence = new stepsSequence(config.name, config.sequence, config.propagate, config.retry)
        stepsSequences << stepsSequence
    }

    void run() {
        def parallelBlocks = [:]

        def counter=1
        jobs.each { item ->
            def index = counter
            parallelBlocks["[Job #"+counter+"] "+item.jobName] = {
                script.stage("[Job #"+index+"] "+item.jobName) {
                    def timeStart = new Date()
                    if( item.retry > 1) {
                        script.retry (item.retry) {
                            script.build (job: item.jobName, parameters: item.parameters, propagate: item.propagate , wait: item.wait)
                        }
                    }
                    else {
                        script.build (job: item.jobName, parameters: item.parameters, propagate: item.propagate , wait: item.wait)
                    }
                    def timeStop = new Date()
                    def duration = TimeCategory.minus(timeStop, timeStart)
                    script.tciLogger.info(" Parallel job ${item.jobName} ended. Duration: ${duration}")
                }
            }
            counter++
        }

        counter=1
        remoteJobs.each { item ->
            def index = counter
            parallelBlocks["[Remote job #"+counter+"] "+item.jobName] = {
                script.stage("[Remote job #"+index+"] "+item.jobName) {
                    def timeStart = new Date()
                    if( item.retry > 1) {
                        script.retry (item.retry) {
                            script.triggerRemoteJob (remoteJenkinsName: item.remoteJenkinsName, job: item.jobName, parameters: item.parameters, abortTriggeredJob: item.propagate, pollInterval: item.pollInterval, useCrumbCache: temm.useCrumbCache, useJobInfoCache: item.useJobInfoCache ,maxConn: 1)
                        }
                    }
                    else {
                        script.triggerRemoteJob (remoteJenkinsName: item.remoteJenkinsName, job: item.jobName, parameters: item.parameters, abortTriggeredJob: item.propagate, pollInterval: item.pollInterval, useCrumbCache: temm.useCrumbCache, useJobInfoCache: item.useJobInfoCache ,maxConn: 1)
                    }
                    def timeStop = new Date()
                    def duration = TimeCategory.minus(timeStop, timeStart)
                    script.tciLogger.info(" Parallel remote job ${item.jobName} ended. Duration: ${duration}")
                }
            }
            counter++
        }

        counter=1
        stepsSequences.each { item ->
            def index = counter
            parallelBlocks["[Sequence #"+counter+"] "+item.sequenceName] = {
                script.stage("[Sequence #"+index+"] "+item.sequenceName) {
                    def timeStart = new Date()
                    if( item.retry > 1) {
                        script.retry (item.retry) {
                            item.sequence()
                        }
                    }
                    else {
                        item.sequence()
                    }
                    def timeStop = new Date()
                    def duration = TimeCategory.minus(timeStop, timeStart)
                    script.tciLogger.info(" Parallel steps-sequence ${item.sequenceName} ended. Duration: ${duration}")
                }
            }
            counter++
        }

        script.tciPipeline.block (name:name) {
            parallelBlocks.failFast = failFast
            script.parallel parallelBlocks
        }
    }
}

