// PulseGuard — cloud CI/CD.
//
// One push to main, and only what actually changed gets tested, built, pushed
// and rolled out:
//
//   git push → GitHub webhook → this pipeline on the AWS Jenkins EC2 instance
//              → detect changed paths
//              → test / build / push / deploy ONLY those components
//
// A Control API change must not run the frontend's tests, must not rebuild the
// worker's image, and must not restart the notification service. That is the
// whole point: a four-component monorepo where every push rebuilds everything
// makes deployments slow and risky in proportion to the size of the repo rather
// than the size of the change.
//
// Nothing here runs on a developer laptop. After `git push` the laptop is done.
//
// Deployment is main-only. Other branches may be built for their tests, but
// nothing reaches ECR or EKS from them.

pipeline {
    agent any

    parameters {
        choice(
            name: 'FORCE_COMPONENT',
            choices: ['none', 'control-api', 'monitor-worker', 'notification-service', 'frontend'],
            description: '''Bootstrap escape hatch. Normally leave on "none" and let
                            path detection decide. Selecting a component processes it
                            as though it had changed, which is how the cloud pipeline
                            was first verified end to end without inventing a fake
                            source change.'''
        )
        booleanParam(
            name: 'RUN_SONAR',
            defaultValue: false,
            description: '''Local-only. SonarQube was never migrated to AWS, so this
                            does nothing on the cloud controller and deployment never
                            depends on it. Retained so the Task 13/14 local workflow
                            still works from this same file.'''
        )
    }

    options {
        // Two overlapping runs would race each other's `kubectl set image` calls
        // and leave the cluster running whichever finished last rather than
        // whichever was pushed last.
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 60, unit: 'MINUTES')
    }

    environment {
        AWS_REGION   = 'us-east-1'
        EKS_CLUSTER  = 'pulseguard-eks'
        K8S_NS       = 'pulseguard'
        DEPLOY_BRANCH = 'main'
        // Node and kubectl were installed under /usr/local/bin by the EC2
        // bootstrap; the Jenkins service PATH does not include it by default.
        PATH = "/usr/local/bin:${env.PATH}"
    }

    stages {

        stage('Environment') {
            steps {
                sh '''
                    echo "branch    : ${BRANCH_NAME:-$GIT_BRANCH}"
                    echo "commit    : $GIT_COMMIT"
                    echo "java      : $(java -version 2>&1 | head -1)"
                    echo "node      : $(node --version)"
                    echo "docker    : $(docker --version)"
                    echo "kubectl   : $(kubectl version --client 2>&1 | head -1)"
                    echo "aws       : $(aws --version 2>&1)"
                '''
                script {
                    // Discovered, never hardcoded — and it doubles as proof the
                    // EC2 instance role is working before anything depends on it.
                    env.AWS_ACCOUNT = sh(
                        script: 'aws sts get-caller-identity --query Account --output text',
                        returnStdout: true).trim()
                    env.ECR_REGISTRY = "${env.AWS_ACCOUNT}.dkr.ecr.${env.AWS_REGION}.amazonaws.com"
                    // 12 hex characters: long enough to be unambiguous, short
                    // enough to read in `kubectl get deployment -o wide`.
                    env.IMAGE_TAG = env.GIT_COMMIT.take(12)
                    echo "registry  : ${env.ECR_REGISTRY}"
                    echo "image tag : ${env.IMAGE_TAG}"
                }
            }
        }

        stage('Detect Changes') {
            steps {
                script {
                    // ----------------------------------------------------------
                    // Which commit do we diff against?
                    // ----------------------------------------------------------
                    // GIT_PREVIOUS_SUCCESSFUL_COMMIT is the right answer when it
                    // exists: it means "everything since the last build that
                    // actually deployed", so a change is never skipped because the
                    // build that would have deployed it failed.
                    //
                    // It is missing on the very first build, and it can point at a
                    // commit this clone does not have after a force-push or a
                    // rebase — hence the existence check rather than trusting it.
                    def base = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT
                    if (base) {
                        def present = sh(script: "git cat-file -e ${base}^{commit} 2>/dev/null",
                                         returnStatus: true) == 0
                        if (!present) {
                            echo "previous successful commit ${base} is not in this clone (force-push or rebase) — ignoring it"
                            base = null
                        }
                    }
                    if (!base) {
                        def hasParent = sh(script: 'git rev-parse --verify HEAD~1 >/dev/null 2>&1',
                                           returnStatus: true) == 0
                        base = hasParent ? 'HEAD~1' : null
                        echo base ? "no previous successful build — comparing HEAD~1..HEAD"
                                  : "repository has a single commit — treating every path as changed"
                    }

                    def changed = base
                        ? sh(script: "git diff --name-only ${base} HEAD", returnStdout: true).trim()
                        : sh(script: 'git ls-files', returnStdout: true).trim()

                    echo "diff range: ${base ?: '(entire tree)'}..HEAD"
                    echo "Changed files:\n${changed ?: '  (none)'}"

                    def files = changed ? changed.split('\n') as List : []
                    def touched = { String prefix -> files.any { it.startsWith(prefix) } }

                    env.CONTROL_CHANGED      = touched('backend/control-api/').toString()
                    env.WORKER_CHANGED       = touched('backend/monitor-worker/').toString()
                    env.NOTIFICATION_CHANGED = touched('backend/notification-service/').toString()
                    env.FRONTEND_CHANGED     = touched('frontend/').toString()

                    if (params.FORCE_COMPONENT != 'none') {
                        echo "FORCE_COMPONENT=${params.FORCE_COMPONENT} — processing it regardless of the diff"
                        switch (params.FORCE_COMPONENT) {
                            case 'control-api':          env.CONTROL_CHANGED      = 'true'; break
                            case 'monitor-worker':       env.WORKER_CHANGED       = 'true'; break
                            case 'notification-service': env.NOTIFICATION_CHANGED = 'true'; break
                            case 'frontend':             env.FRONTEND_CHANGED     = 'true'; break
                        }
                    }

                    env.ANY_CHANGED = ([env.CONTROL_CHANGED, env.WORKER_CHANGED,
                                        env.NOTIFICATION_CHANGED, env.FRONTEND_CHANGED]
                                       .any { it == 'true' }).toString()

                    // Deployment is main-only. A feature branch still gets its
                    // tests, but must not be able to replace what is running.
                    def branch = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: '').replaceAll(/^origin\//, '')
                    env.IS_DEPLOY_BRANCH = (branch == env.DEPLOY_BRANCH).toString()

                    echo """
Affected components:
  control-api          = ${env.CONTROL_CHANGED}
  monitor-worker       = ${env.WORKER_CHANGED}
  notification-service = ${env.NOTIFICATION_CHANGED}
  frontend             = ${env.FRONTEND_CHANGED}

  branch               = ${branch}
  deployable           = ${env.IS_DEPLOY_BRANCH}
"""

                    if (env.ANY_CHANGED != 'true') {
                        // Documentation, the Jenkinsfile itself, k8s manifests,
                        // scripts — real changes, but none of them alter a
                        // container image, so there is nothing to build or roll.
                        echo 'No deployable component changed. Nothing to test, build or deploy.'
                        currentBuild.description = 'no deployable change'
                    } else {
                        currentBuild.description = "tag ${env.IMAGE_TAG}"
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // Tests — only for what changed. Sequential: the EC2 instance has two
        // vCPUs, and parallel JVM builds on it starve each other into flaky
        // failures that have nothing to do with the code.
        // ------------------------------------------------------------------

        stage('Test: Control API') {
            when { environment name: 'CONTROL_CHANGED', value: 'true' }
            steps { dir('backend/control-api') { sh './mvnw -B clean verify' } }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: 'backend/control-api/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Test: Monitor Worker') {
            when { environment name: 'WORKER_CHANGED', value: 'true' }
            steps { dir('backend/monitor-worker') { sh './mvnw -B clean verify' } }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: 'backend/monitor-worker/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Test: Notification Service') {
            when { environment name: 'NOTIFICATION_CHANGED', value: 'true' }
            steps { dir('backend/notification-service') { sh './mvnw -B clean verify' } }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: 'backend/notification-service/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Test: Frontend') {
            when { environment name: 'FRONTEND_CHANGED', value: 'true' }
            steps {
                dir('frontend') {
                    // npm ci, not install: builds exactly what the lockfile pins.
                    sh 'npm ci'
                    sh 'npx vitest run'
                    sh 'npm run typecheck'
                    sh 'npm run build'
                }
            }
        }

        // ------------------------------------------------------------------
        // Publish — only reached when something deployable changed AND we are
        // on the deployment branch.
        // ------------------------------------------------------------------

        stage('ECR Login') {
            when {
                allOf {
                    environment name: 'ANY_CHANGED', value: 'true'
                    environment name: 'IS_DEPLOY_BRANCH', value: 'true'
                }
            }
            steps {
                // Credentials come from the EC2 instance role via the metadata
                // service. No AWS key exists anywhere in Jenkins, and the login
                // token is piped straight into docker rather than stored.
                sh '''
                    aws ecr get-login-password --region "$AWS_REGION" \
                      | docker login --username AWS --password-stdin "$ECR_REGISTRY"
                '''
            }
        }

        stage('Build & Push: Control API') {
            when {
                allOf {
                    environment name: 'CONTROL_CHANGED', value: 'true'
                    environment name: 'IS_DEPLOY_BRANCH', value: 'true'
                }
            }
            steps {
                sh '''
                    IMG="$ECR_REGISTRY/pulseguard-control-api:$IMAGE_TAG"
                    docker build -t "$IMG" backend/control-api
                    docker push "$IMG"
                    echo "pushed $IMG"
                '''
            }
        }

        stage('Build & Push: Monitor Worker') {
            when {
                allOf {
                    environment name: 'WORKER_CHANGED', value: 'true'
                    environment name: 'IS_DEPLOY_BRANCH', value: 'true'
                }
            }
            steps {
                sh '''
                    IMG="$ECR_REGISTRY/pulseguard-monitor-worker:$IMAGE_TAG"
                    docker build -t "$IMG" backend/monitor-worker
                    docker push "$IMG"
                    echo "pushed $IMG"
                '''
            }
        }

        stage('Build & Push: Notification Service') {
            when {
                allOf {
                    environment name: 'NOTIFICATION_CHANGED', value: 'true'
                    environment name: 'IS_DEPLOY_BRANCH', value: 'true'
                }
            }
            steps {
                sh '''
                    IMG="$ECR_REGISTRY/pulseguard-notification-service:$IMAGE_TAG"
                    docker build -t "$IMG" backend/notification-service
                    docker push "$IMG"
                    echo "pushed $IMG"
                '''
            }
        }

        stage('Build & Push: Frontend') {
            when {
                allOf {
                    environment name: 'FRONTEND_CHANGED', value: 'true'
                    environment name: 'IS_DEPLOY_BRANCH', value: 'true'
                }
            }
            steps {
                // --pull on this one specifically. Its runtime base is nginx, and
                // a stale cached base is exactly how the Stage 15D frontend image
                // ended up shipping a CRITICAL openssl CVE.
                sh '''
                    IMG="$ECR_REGISTRY/pulseguard-frontend:$IMAGE_TAG"
                    docker build --pull -t "$IMG" frontend
                    docker push "$IMG"
                    echo "pushed $IMG"
                '''
            }
        }

        // ------------------------------------------------------------------
        // Deploy
        // ------------------------------------------------------------------

        stage('Configure kubectl') {
            when {
                allOf {
                    environment name: 'ANY_CHANGED', value: 'true'
                    environment name: 'IS_DEPLOY_BRANCH', value: 'true'
                }
            }
            steps {
                sh '''
                    aws eks update-kubeconfig --region "$AWS_REGION" --name "$EKS_CLUSTER"

                    # Refuse to touch anything if this is somehow not the cluster
                    # we mean. Cheap check; the failure it prevents is not.
                    CTX=$(kubectl config current-context)
                    echo "context: $CTX"
                    case "$CTX" in
                      *"$EKS_CLUSTER"*) ;;
                      *) echo "ABORT: context does not reference $EKS_CLUSTER"; exit 1 ;;
                    esac

                    # Also proves the namespace-scoped RBAC works before any
                    # mutation is attempted.
                    kubectl get deployments -n "$K8S_NS"
                '''
            }
        }

        stage('Deploy Changed Components') {
            when {
                allOf {
                    environment name: 'ANY_CHANGED', value: 'true'
                    environment name: 'IS_DEPLOY_BRANCH', value: 'true'
                }
            }
            steps {
                script {
                    // Container name == deployment name in every PulseGuard
                    // manifest, which is what makes this table this short.
                    def targets = [
                        'control-api'         : env.CONTROL_CHANGED,
                        'monitor-worker'      : env.WORKER_CHANGED,
                        'notification-service': env.NOTIFICATION_CHANGED,
                        'frontend'            : env.FRONTEND_CHANGED,
                    ].findAll { name, changed -> changed == 'true' }.keySet()

                    targets.each { name ->
                        // Rollout failure must fail the build even though the
                        // rollback succeeds — a green build for a deployment that
                        // did not deploy is worse than a red one.
                        sh """
                            set -e
                            IMG="\$ECR_REGISTRY/pulseguard-${name}:\$IMAGE_TAG"
                            echo "--- ${name} ---"
                            echo "was: \$(kubectl get deployment/${name} -n \$K8S_NS -o jsonpath='{.spec.template.spec.containers[0].image}')"

                            kubectl set image deployment/${name} ${name}="\$IMG" -n "\$K8S_NS"

                            if ! kubectl rollout status deployment/${name} -n "\$K8S_NS" --timeout=10m; then
                                echo "rollout FAILED for ${name} — rolling back"
                                kubectl rollout undo deployment/${name} -n "\$K8S_NS"
                                kubectl rollout status deployment/${name} -n "\$K8S_NS" --timeout=10m || true
                                echo "rolled back; failing the build because the requested deploy did not succeed"
                                exit 1
                            fi

                            echo "now: \$(kubectl get deployment/${name} -n \$K8S_NS -o jsonpath='{.spec.template.spec.containers[0].image}')"
                        """
                    }
                }
            }
        }

        stage('Deployed State') {
            when {
                allOf {
                    environment name: 'ANY_CHANGED', value: 'true'
                    environment name: 'IS_DEPLOY_BRANCH', value: 'true'
                }
            }
            steps {
                // Prints every deployment's image, so the log itself shows that
                // the untouched ones were genuinely left alone.
                sh '''
                    kubectl get deployments -n "$K8S_NS" \
                      -o custom-columns='DEPLOYMENT:.metadata.name,IMAGE:.spec.template.spec.containers[0].image,READY:.status.readyReplicas'
                '''
            }
        }

        stage('SonarQube') {
            // Local-only, off by default, and deployment never depends on it.
            when {
                allOf {
                    expression { return params.RUN_SONAR }
                    environment name: 'ANY_CHANGED', value: 'true'
                }
            }
            steps {
                withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                    sh './scripts/sonar-local.sh'
                }
            }
        }
    }

    post {
        always {
            // Dangling layers only. `docker system prune -a` would evict the
            // base-image cache that keeps these builds to a couple of minutes.
            sh 'docker image prune -f >/dev/null 2>&1 || true'
        }
        success {
            script {
                echo env.ANY_CHANGED == 'true'
                    ? "Deployed ${env.IMAGE_TAG} to ${env.EKS_CLUSTER}/${env.K8S_NS}."
                    : 'No deployable component changed — nothing was built or deployed.'
            }
        }
        failure {
            echo 'Pipeline failed — see the red stage above.'
        }
    }
}
