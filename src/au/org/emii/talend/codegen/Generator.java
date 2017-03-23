package au.org.emii.talend.codegen;

import java.lang.reflect.InvocationTargetException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.talend.commons.CommonsPlugin;
import org.talend.commons.exception.PersistenceException;
import org.talend.commons.exception.SystemException;
import org.talend.core.CorePlugin;
import org.talend.core.context.Context;
import org.talend.core.context.RepositoryContext;
import org.talend.core.model.general.Project;
import org.talend.core.model.process.IContext;
import org.talend.core.model.properties.ProcessItem;
import org.talend.core.model.properties.PropertiesFactory;
import org.talend.core.model.properties.User;
import org.talend.core.model.repository.ERepositoryObjectType;
import org.talend.core.model.repository.IRepositoryViewObject;
import org.talend.core.repository.model.ProxyRepositoryFactory;
import org.talend.core.repository.model.RepositoryFactoryProvider;
import org.talend.designer.codegen.CodeGenInit;
import org.talend.designer.codegen.CodeGeneratorActivator;
import org.talend.designer.codegen.components.ui.IComponentPreferenceConstant;
import org.talend.designer.runprocess.ItemCacheManager;
import org.talend.designer.runprocess.ProcessorException;
import org.talend.repository.ui.wizards.exportjob.JavaJobScriptsExportWSWizardPage.JobExportType;
import org.talend.repository.ui.wizards.exportjob.scriptsmanager.BuildJobManager;
import org.talend.repository.ui.wizards.exportjob.scriptsmanager.JobScriptsManager.ExportChoice;

public class Generator implements IApplication {
	private static Logger log = Logger.getLogger(Generator.class);
	
	private ProxyRepositoryFactory repository;
	
	private Project project;

	@Override
    public Object start(IApplicationContext context) throws Exception {
		
		// Get details of job to export
		String jobName = Params.getMandatoryStringOption("-jobName");
		String projectDir = Params.getMandatoryStringOption("-projectDir");
		String targetDir = Params.getMandatoryStringOption("-targetDir");
		String version = Params.getStringOption("-version", "Latest");
		String componentDir = Params.getStringOption("-componentDir", "");
		
		// Set custom component directory
		
        CodeGeneratorActivator.getDefault().getPreferenceStore()
        .setValue(IComponentPreferenceConstant.USER_COMPONENTS_FOLDER, componentDir);

		// Let talend services know we are running in headless mode
		// so they don't use ui stuff like messageboxes for exceptions
        CommonsPlugin.setHeadless(true);

        // Initialise connection to the local repository (the workspace) 
        repository = connectToRepository();
        
       	// Copy project into workspace
       	project = ProjectUtils.importProject(projectDir);

       	// Log on to project
       	log.info("Logging onto " + project.getLabel() + "...");

        repository.logOnProject(project, new NullProgressMonitor());
        
        //Initialise code generation engine
        log.info("Initialising code generation engine...");

        initCodeGenerationEngine();
        
        // Export the job
		exportJob(jobName, projectDir, targetDir, version);

		// Log off the project
		log.info("Logging off " + project.getLabel() + "...");
		
		repository.logOffProject();
		
		// All good
        return EXIT_OK;
    }

	@Override
    public void stop() {
		// TODO Auto-generated method stub
		
    }

	// Code generation engine must be initialised.  Initialisation loads java_jet template emitters
	// used for code generation
	private void initCodeGenerationEngine() throws Exception {
		CodeGenInit initialiser = new CodeGenInit();
        initialiser.init();
	}

	// Build export file
	private void exportJob(String jobName, String projectDir, String targetDir, String version) throws ProcessorException, InvocationTargetException, InterruptedException, SystemException, CoreException {

		log.info("Exporting " + jobName + "...");

		// Get job to build
		ProcessItem job = getJob(jobName, version);

		String destinationPath = targetDir;
		ProcessItem itemToExport = job;
		String context = IContext.DEFAULT;
		version = job.getProperty().getVersion();
		JobExportType jobExportType = JobExportType.POJO;
		boolean checkCompilationError = true;
		IProgressMonitor monitor = new NullProgressMonitor();
		try {
			log.info("Starting build job manager");
			BuildJobManager.getInstance().buildJob(destinationPath, itemToExport, version, 
					context, getExportChoiceMap(), jobExportType , checkCompilationError , monitor );
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

    // Find specified version of job
	private ProcessItem getJob(String jobName, String version)
			throws PersistenceException {
		List<IRepositoryViewObject> processObjects = repository.getAll(
				project, ERepositoryObjectType.PROCESS, false, false);
		
		for (IRepositoryViewObject processObject : processObjects) {
			if (processObject.getLabel().equals(jobName)) {
				return ItemCacheManager.getProcessItem(processObject.getId(), version);
			}
		}
		
		throw new RuntimeException("Job " + jobName + " not found");
	}

	// Initialise and connect to the local repository (workspace)
	private ProxyRepositoryFactory connectToRepository()
			throws PersistenceException {
		
		ProxyRepositoryFactory repositoryFactory = ProxyRepositoryFactory.getInstance();
        repositoryFactory.setRepositoryFactoryFromProvider(RepositoryFactoryProvider.getRepositoriyById("local")); //$NON-NLS-1$
        repositoryFactory.initialize();

        RepositoryContext repositoryContext = new RepositoryContext();
        repositoryContext.setUser(createUser());
        HashMap<String, String> fields = new HashMap<String, String>();
        repositoryContext.setFields(fields);

        Context ctx = CorePlugin.getContext();
        ctx.putProperty(Context.REPOSITORY_CONTEXT_KEY, repositoryContext);
        
		return repositoryFactory;
	}
	
	protected Map<ExportChoice, Object> getExportChoiceMap() {
        Map<ExportChoice, Object> exportChoiceMap = new EnumMap<ExportChoice, Object>(ExportChoice.class);
        exportChoiceMap.put(ExportChoice.needLauncher, Boolean.TRUE);
        exportChoiceMap.put(ExportChoice.needSystemRoutine, Boolean.TRUE);
        exportChoiceMap.put(ExportChoice.needUserRoutine, Boolean.TRUE);
        exportChoiceMap.put(ExportChoice.needTalendLibraries, Boolean.TRUE);
        exportChoiceMap.put(ExportChoice.needJobItem, Boolean.FALSE);
        exportChoiceMap.put(ExportChoice.needSourceCode, Boolean.FALSE);
        exportChoiceMap.put(ExportChoice.needDependencies, Boolean.TRUE);
        exportChoiceMap.put(ExportChoice.needJobScript, Boolean.TRUE);
      	exportChoiceMap.put(ExportChoice.needContext, Boolean.TRUE);
        exportChoiceMap.put(ExportChoice.contextName, IContext.DEFAULT);
        exportChoiceMap.put(ExportChoice.applyToChildren, Boolean.FALSE);
        exportChoiceMap.put(ExportChoice.binaries, Boolean.TRUE);
        exportChoiceMap.put(ExportChoice.includeLibs, Boolean.TRUE);

        return exportChoiceMap;
    }
	
    // Create a new user
   private User createUser() {
        User user = PropertiesFactory.eINSTANCE.createUser();
        user.setLogin("user@talend.com"); //$NON-NLS-1$
        return user;
    }
   
}
