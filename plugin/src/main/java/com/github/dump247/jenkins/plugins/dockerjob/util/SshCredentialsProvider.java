package com.github.dump247.jenkins.plugins.dockerjob.util;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.SchemeRequirement;
import com.google.inject.Provider;
import hudson.security.ACL;
import jenkins.model.Jenkins;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class SshCredentialsProvider implements Provider<StandardUsernameCredentials> {
    public static final SchemeRequirement SSH_SCHEME = new SchemeRequirement("ssh");

    private final Jenkins _jenkins;
    private final String _credentialsId;

    public SshCredentialsProvider(Jenkins jenkins, String credentialsId) {
        _jenkins = checkNotNull(jenkins);
        _credentialsId = checkNotNull(credentialsId);
    }

    @Override
    public StandardUsernameCredentials get() {
        StandardUsernameCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StandardUsernameCredentials.class,
                        _jenkins,
                        ACL.SYSTEM,
                        SSH_SCHEME),
                CredentialsMatchers.withId(_credentialsId)
        );

        checkState(credentials != null, "No credentials found for id %s", _credentialsId);
        return credentials;
    }
}
