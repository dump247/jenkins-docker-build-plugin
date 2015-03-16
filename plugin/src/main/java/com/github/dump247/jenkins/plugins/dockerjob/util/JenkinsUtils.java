package com.github.dump247.jenkins.plugins.dockerjob.util;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import hudson.model.Node;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;

public class JenkinsUtils {
    public static <T extends Node> FluentIterable<T> getNodes(Jenkins jenkins, Class<T> type) {
        return FluentIterable.from(jenkins.getNodes()).filter(type);
    }

    public static <T extends Cloud> FluentIterable<T> getClouds(Jenkins jenkins, Class<T> type) {
        return FluentIterable.from(jenkins.clouds).filter(type);
    }

    public static <T extends Cloud> Optional<T> getCloud(Jenkins jenkins, Class<T> type, final String name) {
        return getClouds(jenkins, type)
                .filter(new Predicate<T>() {
                    public boolean apply(T input) {
                        return input.getDisplayName().equals(name);
                    }
                })
                .first();
    }
}
