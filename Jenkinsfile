// PulseGuard — continuous integration.
//
// This pipeline answers one question: does the current source build and pass
// its automated checks? It stops there. Nothing is packaged, published or
// deployed, and there is deliberately no Docker or deployment stage — Stage 12
// already proved the images build, and shipping them belongs to a later stage.
//
// It is also infrastructure-independent by design. The suites it runs need no
// MySQL, no Kafka and no SMTP, so a green build here says nothing about those
// and is not meant to: they are verified by hand (see docs/docker.md).

pipeline {
    agent any

    parameters {
        booleanParam(
            name: 'RUN_SONAR',
            defaultValue: false,
            description: '''Also upload SonarQube analysis for the three backends.
                            Off by default: SonarQube is the heaviest thing in the
                            Compose file, and ordinary CI should not need it running.
                            Requires the sonarqube container to be up and a
                            "sonar-token" secret-text credential to exist.'''
        )
    }

    options {
        // One PulseGuard pipeline at a time. Two concurrent runs would put six
        // Maven builds on a laptop that has already shown it saturates, and
        // starved tests fail for reasons that have nothing to do with the code.
        disableConcurrentBuilds()

        // A demonstration instance does not need unbounded history.
        buildDiscarder(logRotator(numToKeepStr: '10'))

        // Generous on purpose. Backend cold starts alone have been measured at
        // several minutes each on this machine; a tight timeout would fail
        // builds for being slow rather than for being wrong.
        timeout(time: 60, unit: 'MINUTES')
    }

    environment {
        // Jenkins and SonarQube share the Compose network, so SonarQube is
        // addressed by service name. Inside this container "localhost" is
        // Jenkins itself — the browser URL (localhost:9000) would find nothing.
        SONAR_HOST_URL = 'http://sonarqube:9000'
    }

    stages {

        stage('Environment') {
            steps {
                // Useful when a build behaves differently here than on a
                // developer machine. Nothing sensitive is printed.
                sh '''
                    echo "workspace : $(pwd)"
                    echo "java      : $(java -version 2>&1 | head -1)"
                    echo "node      : $(node --version)"
                    echo "npm       : $(npm --version)"
                    echo "git       : $(git --version)"
                '''
            }
        }

        // The three backends run one after another rather than in parallel.
        // Each is an independent Maven project with its own wrapper, so they
        // *could* run concurrently — but three JVMs compiling and testing at
        // once is exactly the CPU saturation that made earlier stages flaky.
        // Correctness first; a faster machine can revisit this.

        stage('Control API') {
            steps {
                dir('backend/control-api') {
                    sh './mvnw -B clean verify'
                }
            }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: 'backend/control-api/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Monitor Worker') {
            steps {
                dir('backend/monitor-worker') {
                    sh './mvnw -B clean verify'
                }
            }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: 'backend/monitor-worker/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Notification Service') {
            steps {
                dir('backend/notification-service') {
                    sh './mvnw -B clean verify'
                }
            }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: 'backend/notification-service/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Frontend') {
            stages {
                stage('Install') {
                    steps {
                        dir('frontend') {
                            // npm ci, not npm install: installs exactly what the
                            // committed lockfile pins and fails if the two have
                            // drifted, rather than quietly resolving something
                            // newer during a CI run.
                            sh 'npm ci'
                        }
                    }
                }
                stage('Tests') {
                    steps {
                        dir('frontend') {
                            sh 'npx vitest run'
                        }
                    }
                }
                stage('Typecheck') {
                    steps {
                        dir('frontend') {
                            sh 'npm run typecheck'
                        }
                    }
                }
                stage('Build') {
                    steps {
                        dir('frontend') {
                            // A production build that fails is a broken change,
                            // even when every test passed.
                            sh 'npm run build'
                        }
                    }
                }
            }
        }

        stage('SonarQube') {
            when {
                expression { return params.RUN_SONAR }
            }
            steps {
                // The token is bound only for this block and only as a shell
                // variable. The sh script is single-quoted so Groovy never
                // interpolates it — the value must not reach the build log,
                // and Groovy interpolation is how it usually does.
                withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                    // Reuses the Stage 13 script rather than restating the scan
                    // commands here. It already covers exactly the three
                    // backends and deliberately excludes the frontend, whose
                    // analyzer rejects this project's Node version.
                    sh './scripts/sonar-local.sh'
                }
            }
        }
    }

    post {
        success {
            echo 'PulseGuard CI passed.'
        }
        failure {
            // Deliberately no email, Slack or webhook. PulseGuard's own
            // notification service delivers incident email; wiring build
            // notifications through anything is a separate concern and not
            // part of this stage.
            echo 'PulseGuard CI failed — see the stage above that went red.'
        }
    }
}
