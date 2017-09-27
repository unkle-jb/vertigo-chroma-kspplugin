package io.vertigo.chroma.kspplugin.resources.core;

import io.vertigo.chroma.kspplugin.model.FileRegion;
import io.vertigo.chroma.kspplugin.model.JavaProjectMap;
import io.vertigo.chroma.kspplugin.model.Navigable;
import io.vertigo.chroma.kspplugin.utils.ErrorUtils;
import io.vertigo.chroma.kspplugin.utils.ResourceUtils;
import io.vertigo.chroma.kspplugin.utils.UiUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.ui.editors.text.TextFileDocumentProvider;

/**
 * Magasin des ressources d'un workspace.
 * <p>
 * <ul>
 * <li>parcourt tous les fichiers du workspace</li>
 * <li>extrait des �l�ments des fichiers</li>
 * <li>publie une API de recherche des �l�ments</li>
 * <li>se maintient automatiquement � jour des modifications du workspace</li>
 * </ul>
 * </p>
 * 
 * @param <T> Type de l'�l�ment du magasin.
 */
public class ResourceStore<T extends Navigable> implements IResourceChangeListener {

	private final ResourceStoreImplementor<T> implementor;
	private final ItemMap map = new ItemMap();
	private static final TextFileDocumentProvider DOCUMENT_PROVIDER = new TextFileDocumentProvider();

	/**
	 * Cr�� une nouvelle instance de ResourceStore.
	 * 
	 * @param implementor Impl�menteur.
	 */
	public ResourceStore(ResourceStoreImplementor<T> implementor) {
		this.implementor = implementor;
	}

	/**
	 * D�marrage du magasin.
	 */
	public void start() {
		initStore();
		initListener();
	}

	@Override
	public void resourceChanged(IResourceChangeEvent event) {
		if (event.getDelta() == null) {
			/* Ev�nement sans delta : on ne traite pas. */
			return;
		}

		/* Visite l'arborescence du delta. */
		try {
			event.getDelta().accept(new DeltaItemVisitor());
		} catch (CoreException e) {
			ErrorUtils.handle(e);
		}
	}

	/**
	 * Renvoie � plat la liste de tous les �l�ments de tous les fichiers.
	 * 
	 * @return Liste des �l�ments.
	 */
	public List<T> getAllItems() {
		return map.getAll();
	}

	/**
	 * Trouve le premier �l�ment du magasin validant un pr�dicat.
	 * 
	 * <p>
	 * La recherche se fait dans le projet courant.
	 * </p>
	 * 
	 * @param predicate Pr�dicat.
	 * @return Premier �l�ment, <code>null</code> sinon.
	 */
	public T findFirstItem(Predicate<T> predicate) {

		/* Obtient le projet courant. */
		IProject currentProject = UiUtils.getCurrentEditorProject();

		List<T> items = this.getAllItems();
		for (T item : items) {

			/* Filtre sur le projet. */
			if (currentProject != null && !currentProject.equals(ResourceUtils.getProject(item))) {
				continue;
			}

			/* Filtre avec le pr�dicat. */
			if (predicate.test(item)) {
				return item;
			}
		}

		return null;
	}

	/**
	 * Trouve tous les �l�ments du magasin validant un pr�dicat.
	 * 
	 * @param predicate Pr�dicat.
	 * @return Les �l�ments trouv�s.
	 */
	public List<T> findAllItems(Predicate<T> predicate) {

		/* Obtient le nom du projet courant. */
		IProject currentProject = UiUtils.getCurrentEditorProject();

		List<T> found = new ArrayList<>();

		List<T> items = this.getAllItems();
		for (T item : items) {

			/* Filtre sur le nom du projet. */
			if (currentProject != null && !currentProject.equals(ResourceUtils.getProject(item))) {
				continue;
			}

			if (predicate.test(item)) {
				found.add(item);
			}
		}

		return found;
	}

	/**
	 * Trouve l'�l�ment contenant la r�gion de fichier donn�e.
	 * 
	 * @param fileRegion R�gion de fichier.
	 * @return Premier �l�ment, <code>null</code> sinon.
	 */
	public T findItem(FileRegion fileRegion) {

		/* Obtient les items du fichier recherch�. */
		List<T> items = this.map.get(fileRegion.getFile());
		if (items == null) {
			return null;
		}

		T lastMatchingItem = null;
		int offset = fileRegion.getOffset();

		/* Parcourt des items du fichier. */
		for (T item : items) {

			int itemOffset = item.getFileRegion().getOffset();

			/* L'�l�ment est avant l'offset recherch� : on le note comme dernier trouv�. */
			if (itemOffset < offset) {
				lastMatchingItem = item;
			}

			/* L'�l�ment est apr�s l'offset recherch� : on renvoie le dernier �l�ment trouv�. */
			if (itemOffset > offset) {
				return lastMatchingItem;
			}
		}

		/* Cas avec une seule d�claration. */
		return lastMatchingItem;
	}

	/**
	 * Initialise le magasin.
	 */
	private void initStore() {

		/* Charge les projets ouverts. */
		JavaProjectMap projectMap = ResourceUtils.getProjectMap();

		try {
			/* Parcourt les projets ouverts. */
			for (Entry<IProject, IJavaProject> entry : projectMap.entrySet()) {

				IProject project = entry.getKey();
				IJavaProject javaProject = entry.getValue();

				/* Parcours les resources du projet. */
				project.accept(new ItemVisitor(project, javaProject));
			}
		} catch (Exception e) {
			ErrorUtils.handle(e);
		}
	}

	/**
	 * Initialise le listener de ressources du workspace.
	 */
	private void initListener() {
		/* Comme la dur�e de vie du store est celle du plugin, il n'est pas n�cessaire de pr�voir de se d�sabonner. */
		ResourcesPlugin.getWorkspace().addResourceChangeListener(this);
	}

	/**
	 * Traite un fichier.
	 * 
	 * @param fileProvider Fournisseur de fichier.
	 */
	private void handleFile(FileProvider fileProvider) {
		IFile file = fileProvider.getFile();
		List<T> items = implementor.getItems(fileProvider);
		if (items == null || items.isEmpty()) {
			/* Le fichier ne contient aucun item : on le supprime s'il existe. */
			map.remove(file);
			return;
		}
		/* Le fichier contient des items : on l'index en rempla�ant la version pr�c�dente. */
		map.put(file, items);
	}

	/**
	 * Visiteur des ressources du workspace.
	 */
	private class ItemVisitor implements IResourceVisitor {

		private final IProject project;
		private final IJavaProject javaProject;

		public ItemVisitor(IProject project, IJavaProject javaProject) {
			this.project = project;
			this.javaProject = javaProject;
		}

		@Override
		public boolean visit(IResource resource) throws CoreException {

			if (ResourceUtils.isTargetFolder(resource)) {
				/* Dossier target : on n'explore pas. */
				return false;
			}

			if (resource.getType() != IResource.FILE) {
				/* Dossier : on continue la visite. */
				return true;
			}

			/* La ressource est un fichier. */
			IFile file = (IFile) resource;

			/* V�rification que le fichier est candidat. */
			if (!implementor.isCandidate(file)) {
				return false;
			}

			/* Traitement du fichier. */
			FileProvider fileProvider = new FileProvider(file, project, javaProject, DOCUMENT_PROVIDER);
			handleFile(fileProvider);

			return false;
		}
	}

	/**
	 * Visiteur d'un delta des ressources workspace.
	 */
	private class DeltaItemVisitor implements IResourceDeltaVisitor {

		private final JavaProjectMap projectMap = ResourceUtils.getProjectMap();

		@Override
		public boolean visit(IResourceDelta delta) throws CoreException {
			IResource resource = delta.getResource();

			/* Dossier target : on n'explore pas. */
			if (ResourceUtils.isTargetFolder(resource)) {
				return false;
			}

			/* Filtre sur les fichiers. */
			if (!(resource instanceof IFile)) {
				/* Dossier : on continue la visite. */
				return true;
			}

			/* La ressource est un fichier. */
			IFile file = (IFile) resource;

			/* V�rifie que le magasin est concern� par ce fichier. */
			boolean isCandidate = implementor.isCandidate(file);
			if (!isCandidate) {
				return false;
			}

			switch (delta.getKind()) {
			case IResourceDelta.REMOVED:
			case IResourceDelta.REMOVED_PHANTOM:
				/* Cas d'une suppression de fichier. */
				handleDeletion(file);
				break;
			default:
				/* Cas d'un ajout ou d'un changement : on traite le fichier. */
				handleChange(file);
				break;
			}

			/* Fin de la visite. */
			return false;
		}

		/**
		 * Traite un changement de fichier.
		 * 
		 * @param file Fichier.
		 */
		private void handleChange(IFile file) {
			/* Construit le fournisseur de fichier. */
			IProject project = file.getProject();
			IJavaProject javaProject = projectMap.get(project);
			FileProvider fileProvider = new FileProvider(file, project, javaProject, DOCUMENT_PROVIDER);

			/* Traite le fichier. */
			handleFile(fileProvider);
		}

		/**
		 * Traite la suppression d'un fichier du workspace.
		 * 
		 * @param file Fichier.
		 */
		private void handleDeletion(IFile file) {
			/* On d�sindexe le fichier et on sort. */
			map.remove(file);
		}
	}

	/**
	 * Map associant un fichier � ses �l�ments.
	 * <p>
	 * On utilise une concurrent hash map pour g�rer les acc�s concurrents (initialisation au d�marrage d'Eclipse, recherche de DTO).
	 * </p>
	 */
	private class ItemMap extends ConcurrentHashMap<IFile, List<T>> {
		private static final long serialVersionUID = 1L;

		/**
		 * Renvoie tous les �l�ments de tous les fichiers.
		 * 
		 * @return Liste des �l�ments.
		 */
		public List<T> getAll() {
			/* Renvoie l'union de tous les �l�ments de tous les fichiers. */
			return this.values().stream().flatMap(List::stream).collect(Collectors.toList());
		}
	}
}