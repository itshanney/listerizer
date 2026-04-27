pipeline {
    // Executes the pipeline on any available Jenkins agent
    agent any

    tools {
        gradle 'gradle-9.4.1'
        jdk 'jdk-25'
    }

    // Defines environment variables available to all stages
    environment {
        APP_NAME = "listerizer"

        JAR_NAME = "listerizer.jar"
        TARGET_DIR = "/var/lib/${APP_NAME}/download"
        ANSIBLE_INVENTORY = "ansible/inventory/listerizer"
        ANSIBLE_PLAYBOOK = "ansible/deploy.yml"
    }

    stages {
        stage('Build') {
            steps {
                echo "Building ${APP_NAME}..."
                sh 'gradle assemble -x test'
            }
        }

        stage('Unit Test') {
            steps {
                echo 'Running unit tests...'
                sh 'gradle test'
            }
        }

        stage('Integration Test') {
            steps {
                echo 'Running integration tests...'
                sh 'gradle integrationTest'
            }
        }

        stage('Deploy') {
            steps {
                echo "Deploying ${APP_NAME} to brickone server..."

                // 1. Create the directory (if it doesn't exist) and copy the JAR
                // Using standard Linux commands via the 'sh' step
                echo "Copying artifact to ${TARGET_DIR}..."
                sh "mkdir -p ${TARGET_DIR}"

                // We copy *.jar and rename it cleanly so the Ansible script always knows the exact filename
                sh "cp build/libs/listerizer.jar ${TARGET_DIR}/${JAR_NAME}"

                // 2. Execute the Ansible playbook
                // Passing variables directly to Ansible using --extra-vars
                echo "Executing Ansible playbook..."
                sh """
                    ansible-playbook -i ${ANSIBLE_INVENTORY} \
                                     ${ANSIBLE_PLAYBOOK} \
                                     --extra-vars "new_jar=${TARGET_DIR}/${JAR_NAME} app_name=${APP_NAME}"
                """
            }
        }
    }

    // The post section runs after all stages finish, regardless of success/failure
    post {
        always {
            echo 'Pipeline finished. Archiving test results...'
            // Tells Jenkins to ingest the JUnit XML files to generate the UI test reports
            junit 'build/test-results/**/*.xml'

            // Saves the actual compiled JAR so you can download it from the Jenkins UI
            archiveArtifacts artifacts: 'build/libs/*.jar', fingerprint: true
        }
        success {
            echo '✅ Pipeline completed successfully!'
            // You could add Slack or Email notification commands here
        }
        failure {
            echo '❌ Pipeline failed. Please check the logs.'
        }
    }
}
