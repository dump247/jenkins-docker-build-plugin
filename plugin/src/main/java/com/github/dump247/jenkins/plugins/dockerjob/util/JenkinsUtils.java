package com.github.dump247.jenkins.plugins.dockerjob.util;

import com.google.common.collect.FluentIterable;
import hudson.model.Node;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;

public class JenkinsUtils {
    public static <T extends Node> Iterable<T> getNodes(Jenkins jenkins, Class<T> type) {
        return FluentIterable.from(jenkins.getNodes()).filter(type);
    }

    public static <T extends Cloud> Iterable<T> getClouds(Jenkins jenkins, Class<T> type) {
        return FluentIterable.from(jenkins.clouds).filter(type);
    }
}
