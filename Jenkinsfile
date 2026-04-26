pipeline {
    agent any

    tools {
        jdk 'jdk-25'
    }

    stages {
        stage('Build & Test') {
            steps {
                sh './gradlew build --no-daemon'
            }
            post {
                always {
                    junit 'build/test-results/test/*.xml'
                }
            }
        }
    }
}
