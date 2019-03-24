package tci.pipeline

import groovy.time.TimeCategory;

class parallelPhase implements Serializable {

    class subJob implements Serializable {

        String jobName
        def parameters
        boolean propagate
        boolean wait
        int retry
        String status
        String url
        def duration
        String title

        subJob(String jobName, def parameters, boolean propagate, boolean wait, int retry ) {
            this.jobName = jobName
            this.parameters = parameters
            this.propagate = propagate
            this.wait = wait
            this.retry = retry
        }
    }

    class stepsSequence implements Serializable {

        String sequenceName
        def sequence
        boolean propagate
        boolean wait
        int retry
        String status
        String url
        def duration
        String title

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
    def stepsSequences = []
    boolean failFast = false
    boolean failOnError = false
    String overAllStatus = "SUCCESS"
    String description = ""

    parallelPhase(script, String name = "TCI parallel", boolean failFast = false, boolean failOnError = false) {
        this.script = script
        this.name = name
        this.failFast = failFast
        this.failOnError = failOnError
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

    @NonCPS
    def getBuildResult(def build) {
        try {
            return build.getResult()
        }
        catch (error) {
            script.echo "[ERROR] [getBuildResult] "+error.message
        }
    }

    @NonCPS
    def getBuildUrl(def build) {
        try {
            return build.getAbsoluteUrl()
        }
        catch (error) {
            script.echo "[ERROR] [getBuildUrl] "+error.message
        }
    }

    def runJob(def item) {
        def timeStart = new Date()
        if(item.retry < 1) {
            item.retry = 1
        }
        def count=0
        while (count < item.retry) {
            try {
                count++
                def currentRun = script.build (job: item.jobName, parameters: item.parameters, propagate: false , wait: item.wait)
                if(currentRun!=null) {
                    item.status = getBuildResult(currentRun)
                    item.url = getBuildUrl(currentRun)
                }
                if(item.status=="SUCCESS" || item.status=="ABORTED") {
                    count=item.retry
                }
            }
            catch (error) {
                script.echo error.message
                item.status = "FAILURE"
            }
        }
        def timeStop = new Date()
        def duration = TimeCategory.minus(timeStop, timeStart)
        script.tciLogger.info(" Parallel job '\033[1;94m${item.jobName}\033[0m' ended. Duration: \033[1;94m${duration}\033[0m")
    }

    def runStepsSequence(def item) {
        def timeStart = new Date()
        if(item.retry < 1) {
            item.retry = 1
        }
        def count=0
        while (count < item.retry) {
            try {
                count++
                item.sequence()
                count=item.retry
            }
            catch (error) {
                script.echo error.message
                item.status = "FAILURE"
            }
        }
        def timeStop = new Date()
        def duration = TimeCategory.minus(timeStop, timeStart)
        script.tciLogger.info(" Parallel steps-sequence '\033[1;94m${item.sequenceName}\033[0m' ended. Duration: \033[1;94m${duration}\033[0m")
    }

    def setOverallStatusByItem(def item) {
        if(item.propagate == true) {
            if(item.status == "FAILURE") {
                overAllStatus="FAILURE"
            }
            else {
                if(item.status == "UNSTABLE") {
                    if(item.overAllStatus != "FAILURE") {
                        overAllStatus="UNSTABLE"
                    }
                }
                else {
                    if(item.status == "ABORTED") {
                        if(item.overAllStatus != "FAILURE" && item.overAllStatus != "UNSTABLE") {
                            overAllStatus="ABORTED"
                        }
                    }
                }
            }
        }
    }

    void run() {
        def parallelBlocks = [:]

        def counter=1
        jobs.each { item ->
            def index = counter
            def title = "[Job #"+counter+"] "+item.jobName
            item.title = title
            item.status = "SUCCESS"
            item.url = ""
            parallelBlocks[title] = {
                script.stage(title) {
                    runJob(item)
                    setOverallStatusByItem(item)
                }
            }
            counter++
        }

        counter=1
        stepsSequences.each { item ->
            def index = counter
            def title = "[Sequence #"+counter+"] "+item.sequenceName
            item.title = title
            parallelBlocks[title] = {
                script.stage(title) {
                    item.status = "SUCCESS"
                    runStepsSequence(item)
                    setOverallStatusByItem(item)
                }
            }
            counter++
        }

        script.tciPipeline.block (name:name,failOnError:failOnError) {
            parallelBlocks.failFast = failFast
            try {
                script.parallel parallelBlocks
            }
            catch (error) {
            }

            description = "\033[1;94m"+name+'\033[0m\n'
            jobs.each { item ->
                def currentStatus = item.status
                if(item.propagate == false) {
                    currentStatus += " (propagate:false)"
                }
                description += '\t'+item.title+' - '+currentStatus+' - '+item.url+'\n'
            }
            stepsSequences.each { item ->
                def currentStatus = item.status
                if(item.propagate == false) {
                    currentStatus += " (propagate:false)"
                }
                description += '\t'+item.title+' - '+currentStatus+'\n'
            }
            String statusColor="\033[0;102m"
            if(overAllStatus=="FAILURE") {
                statusColor="\033[0;101m"
            }
            else {
                if(overAllStatus=="UNSTABLE") {
                    statusColor="\033[0;103m"
                }
                else {
                    if(overAllStatus=="ABORTED") {
                        statusColor="\033[0;100m"
                    }
                    else {

                    }
                }
            }
            description += "'"+name+"' phase status: "+statusColor+overAllStatus+'\033[0m\n'
            script.echo description
            script.currentBuild.result = overAllStatus
//            if (overAllStatus=="FAILURE" || overAllStatus=="ABORTED") {
//                script.echo "\033[1;91m[ERROR]\033[0m phase '${name}' Failed"
//            }
        }
    }
}

