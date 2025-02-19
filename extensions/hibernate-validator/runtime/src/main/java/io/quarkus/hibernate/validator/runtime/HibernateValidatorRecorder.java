package io.quarkus.hibernate.validator.runtime;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import javax.enterprise.util.TypeLiteral;
import javax.validation.ClockProvider;
import javax.validation.ConstraintValidatorFactory;
import javax.validation.MessageInterpolator;
import javax.validation.ParameterNameProvider;
import javax.validation.TraversableResolver;
import javax.validation.Validation;
import javax.validation.ValidatorFactory;
import javax.validation.valueextraction.ValueExtractor;

import org.hibernate.validator.PredefinedScopeHibernateValidator;
import org.hibernate.validator.PredefinedScopeHibernateValidatorConfiguration;
import org.hibernate.validator.internal.engine.resolver.JPATraversableResolver;
import org.hibernate.validator.spi.messageinterpolation.LocaleResolver;
import org.hibernate.validator.spi.nodenameprovider.PropertyNodeNameProvider;
import org.hibernate.validator.spi.properties.GetterPropertySelectionStrategy;
import org.hibernate.validator.spi.scripting.ScriptEvaluatorFactory;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.arc.runtime.BeanContainerListener;
import io.quarkus.hibernate.validator.HibernateValidatorFactoryCustomizer;
import io.quarkus.hibernate.validator.runtime.jaxrs.ResteasyConfigSupport;
import io.quarkus.runtime.LocalesBuildTimeConfig;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class HibernateValidatorRecorder {

    private static final TypeLiteral<ValueExtractor<?>> TYPE_LITERAL_VALUE_EXTRACTOR_WITH_WILDCARD = new TypeLiteral<ValueExtractor<?>>() {
    };

    public BeanContainerListener initializeValidatorFactory(Set<Class<?>> classesToBeValidated,
            Set<String> detectedBuiltinConstraints,
            boolean hasXmlConfiguration, boolean jpaInClasspath,
            ShutdownContext shutdownContext, LocalesBuildTimeConfig localesBuildTimeConfig,
            HibernateValidatorBuildTimeConfig hibernateValidatorBuildTimeConfig) {
        BeanContainerListener beanContainerListener = new BeanContainerListener() {

            @Override
            public void created(BeanContainer container) {
                PredefinedScopeHibernateValidatorConfiguration configuration = Validation
                        .byProvider(PredefinedScopeHibernateValidator.class)
                        .configure();

                if (!hasXmlConfiguration) {
                    configuration.ignoreXmlConfiguration();
                }

                LocaleResolver localeResolver = null;
                InstanceHandle<LocaleResolver> configuredLocaleResolver = Arc.container()
                        .instance("locale-resolver-wrapper");
                if (configuredLocaleResolver.isAvailable()) {
                    localeResolver = configuredLocaleResolver.get();
                    configuration.localeResolver(localeResolver);
                }

                configuration
                        .builtinConstraints(detectedBuiltinConstraints)
                        .initializeBeanMetaData(classesToBeValidated)
                        .locales(localesBuildTimeConfig.locales)
                        .defaultLocale(localesBuildTimeConfig.defaultLocale)
                        .beanMetaDataClassNormalizer(new ArcProxyBeanMetaDataClassNormalizer());

                if (hibernateValidatorBuildTimeConfig.expressionLanguage.constraintExpressionFeatureLevel.isPresent()) {
                    configuration.constraintExpressionLanguageFeatureLevel(
                            hibernateValidatorBuildTimeConfig.expressionLanguage.constraintExpressionFeatureLevel.get());
                }

                InstanceHandle<ConstraintValidatorFactory> configuredConstraintValidatorFactory = Arc.container()
                        .instance(ConstraintValidatorFactory.class);
                if (configuredConstraintValidatorFactory.isAvailable()) {
                    configuration.constraintValidatorFactory(configuredConstraintValidatorFactory.get());
                } else {
                    configuration.constraintValidatorFactory(new ArcConstraintValidatorFactoryImpl());
                }

                InstanceHandle<MessageInterpolator> configuredMessageInterpolator = Arc.container()
                        .instance(MessageInterpolator.class);
                if (configuredMessageInterpolator.isAvailable()) {
                    configuration.messageInterpolator(configuredMessageInterpolator.get());
                }

                InstanceHandle<TraversableResolver> configuredTraversableResolver = Arc.container()
                        .instance(TraversableResolver.class);
                if (configuredTraversableResolver.isAvailable()) {
                    configuration.traversableResolver(configuredTraversableResolver.get());
                } else {
                    // we still define the one we want to use so that we do not rely on runtime automatic detection
                    if (jpaInClasspath) {
                        configuration.traversableResolver(new JPATraversableResolver());
                    } else {
                        configuration.traversableResolver(new TraverseAllTraversableResolver());
                    }
                }

                InstanceHandle<ParameterNameProvider> configuredParameterNameProvider = Arc.container()
                        .instance(ParameterNameProvider.class);
                if (configuredParameterNameProvider.isAvailable()) {
                    configuration.parameterNameProvider(configuredParameterNameProvider.get());
                }

                InstanceHandle<ClockProvider> configuredClockProvider = Arc.container().instance(ClockProvider.class);
                if (configuredClockProvider.isAvailable()) {
                    configuration.clockProvider(configuredClockProvider.get());
                }

                // Hibernate Validator-specific configuration

                configuration.failFast(hibernateValidatorBuildTimeConfig.failFast);
                configuration.allowOverridingMethodAlterParameterConstraint(
                        hibernateValidatorBuildTimeConfig.methodValidation.allowOverridingParameterConstraints);
                configuration.allowParallelMethodsDefineParameterConstraints(
                        hibernateValidatorBuildTimeConfig.methodValidation.allowParameterConstraintsOnParallelMethods);
                configuration.allowMultipleCascadedValidationOnReturnValues(
                        hibernateValidatorBuildTimeConfig.methodValidation.allowMultipleCascadedValidationOnReturnValues);

                InstanceHandle<ScriptEvaluatorFactory> configuredScriptEvaluatorFactory = Arc.container()
                        .instance(ScriptEvaluatorFactory.class);
                if (configuredScriptEvaluatorFactory.isAvailable()) {
                    configuration.scriptEvaluatorFactory(configuredScriptEvaluatorFactory.get());
                }

                InstanceHandle<GetterPropertySelectionStrategy> configuredGetterPropertySelectionStrategy = Arc.container()
                        .instance(GetterPropertySelectionStrategy.class);
                if (configuredGetterPropertySelectionStrategy.isAvailable()) {
                    configuration.getterPropertySelectionStrategy(configuredGetterPropertySelectionStrategy.get());
                }

                InstanceHandle<PropertyNodeNameProvider> configuredPropertyNodeNameProvider = Arc.container()
                        .instance(PropertyNodeNameProvider.class);
                if (configuredPropertyNodeNameProvider.isAvailable()) {
                    configuration.propertyNodeNameProvider(configuredPropertyNodeNameProvider.get());
                }

                // Automatically add all the values extractors declared as beans
                for (ValueExtractor<?> valueExtractor : Arc.container().beanManager().createInstance()
                        // Apparently passing ValueExtractor.class
                        // won't match beans implementing ValueExtractor<NotAWildcard>,
                        // so we need a type literal here.
                        .select(TYPE_LITERAL_VALUE_EXTRACTOR_WITH_WILDCARD)) {
                    configuration.addValueExtractor(valueExtractor);
                }

                List<InstanceHandle<HibernateValidatorFactoryCustomizer>> constraintMappingProviderList = Arc.container()
                        .listAll(HibernateValidatorFactoryCustomizer.class);
                for (InstanceHandle<HibernateValidatorFactoryCustomizer> cmpInstanceHandle : constraintMappingProviderList) {
                    if (cmpInstanceHandle.isAvailable()) {
                        final HibernateValidatorFactoryCustomizer hibernateValidatorFactoryCustomizer = cmpInstanceHandle.get();
                        hibernateValidatorFactoryCustomizer.customize(configuration);
                    }
                }

                ValidatorFactory validatorFactory = configuration.buildValidatorFactory();
                ValidatorHolder.initialize(validatorFactory);

                // Close the ValidatorFactory on shutdown
                shutdownContext.addShutdownTask(new Runnable() {
                    @Override
                    public void run() {
                        validatorFactory.close();
                    }
                });
            }
        };

        return beanContainerListener;
    }

    public Supplier<ResteasyConfigSupport> resteasyConfigSupportSupplier(boolean jsonDefault) {
        return new Supplier<ResteasyConfigSupport>() {

            @Override
            public ResteasyConfigSupport get() {
                return new ResteasyConfigSupport(jsonDefault);
            }
        };
    }
}
