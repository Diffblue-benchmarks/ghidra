/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package docking;

import java.awt.*;
import java.awt.dnd.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.FocusManager;

import docking.action.DockingActionIf;
import docking.help.HelpService;
import ghidra.util.CascadedDropTarget;
import ghidra.util.HelpLocation;

/**
 * Wrapper class for user components. Adds the title, local toolbar and provides the drag target
 * functionality.
 */
public class DockableComponent extends JPanel implements ContainerListener {
	private static final int DROP_EDGE_OFFSET = 20;

	private static final Dimension MIN_DIM = new Dimension(100, 50);

	public static DropCode DROP_CODE;
	public static ComponentPlaceholder TARGET_INFO;
	public static ComponentPlaceholder DRAGGED_OVER_INFO;
	public static ComponentPlaceholder SOURCE_INFO;
	public static boolean DROP_CODE_SET;

	enum DropCode {
		INVALID, STACK, LEFT, RIGHT, TOP, BOTTOM, ROOT, WINDOW
	}

	private DockableHeader header;
	private MouseListener popupListener;
	private ComponentPlaceholder componentInfo;
	private JComponent providerComp;
	private Component focusedComponent;
	private DockingWindowManager winMgr;
	private ActionToGuiMapper actionMgr;
	private DropTarget dockableDropTarget;

	/**
	 * Constructs a new DockableComponent for the given info object.
	 * @param placeholder the info object that has the component to be shown.
	 * @param isDocking if true allows components to be dragged and docked.
	 */
	DockableComponent(ComponentPlaceholder placeholder, boolean isDocking) {
		if (placeholder != null) {
			this.componentInfo = placeholder;

			winMgr = placeholder.getNode().winMgr;
			actionMgr = winMgr.getActionToGuiMapper();

			popupListener = new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					componentSelected((Component) e.getSource());
					processPopupMouseEvent(e);
				}

				@Override
				public void mouseReleased(MouseEvent e) {
					processPopupMouseEvent(e);
				}

				@Override
				public void mouseClicked(MouseEvent e) {
					processPopupMouseEvent(e);
				}
			};

			dockableDropTarget = new DockableComponentDropTarget(this);
			initializeComponents(this);

			setLayout(new BorderLayout());
			header = new DockableHeader(this, isDocking);
			if (placeholder.isHeaderShowing()) {
				add(header, BorderLayout.NORTH);
			}

			providerComp = initializeComponentPlaceholder(placeholder);

			JPanel contentPanel = new JPanel(new BorderLayout());
			setFocusable(false); // this should never be focusable

			setFocusCycleRoot(false);
			contentPanel.add(providerComp, BorderLayout.CENTER);
			add(contentPanel, BorderLayout.CENTER);
		}
		else {
			dockableDropTarget = new DockableComponentDropTarget(this);
		}
	}

	private JComponent initializeComponentPlaceholder(ComponentPlaceholder placeholder) {
		JComponent providerComponent = placeholder.getProviderComponent();

		// Ensure that every provider component has a registered help location
		ComponentProvider provider = placeholder.getProvider();
		HelpLocation helpLocation = provider.getHelpLocation();
		HelpLocation location = registerHelpLocation(provider, helpLocation);

		header.setHelp(location);

		return providerComponent;
	}

	public DockableHeader getHeader() {
		return header;
	}

	private HelpLocation registerHelpLocation(ComponentProvider provider,
			HelpLocation helpLocation) {
		HelpService helpService = DockingWindowManager.getHelpService();
		if (helpService.isExcludedFromHelp(provider)) {
			return null;
		}

		HelpLocation registeredHelpLocation = helpService.getHelpLocation(provider);
		if (registeredHelpLocation != null) {
			return registeredHelpLocation; // nothing to do; location already registered
		}

		if (helpLocation == null) {
			// this shouldn't happen, but just in case
			helpLocation = new HelpLocation(provider.getOwner(), provider.getName());
		}

		helpService.registerHelp(provider, helpLocation);
		return helpLocation;
	}

	public Component getFocusedComponent() {
		return focusedComponent;
	}

	private void processPopupMouseEvent(final MouseEvent e) {
		Component component = e.getComponent();
		if (component == null) {
			return;
		}

		// get the bounds to see if the clicked point is over the component
		Rectangle bounds = component.getBounds(); // get bounds to get width and height

		if (component instanceof JComponent) {
			((JComponent) component).computeVisibleRect(bounds);
		}

		Point point = e.getPoint();
		boolean withinBounds = bounds.contains(point);

		if (e.isPopupTrigger() && withinBounds) {
			actionMgr.showPopupMenu(componentInfo, e);
		}
	}

	/**
	 * @see java.awt.Component#getMinimumSize()
	 */
	@Override
	public Dimension getMinimumSize() {
		return MIN_DIM;
	}

	/**
	 * Returns the user component that this wraps.
	 */
	JComponent getProviderComponent() {
		return providerComp;
	}

	/**
	 * Returns the info object associated with this DockableComponent.
	 */
	public ComponentPlaceholder getComponentWindowingPlaceholder() {
		return componentInfo;
	}

	@Override
	public String toString() {
		if (componentInfo == null) {
			return "";
		}
		return componentInfo.getFullTitle();
	}

	/**
	 * Set up for drag and drop.
	 *
	 */

	private void translate(Point p, Component c) {
		Point cLoc = c.getLocationOnScreen();
		Point myLoc = getLocationOnScreen();
		p.x = p.x + cLoc.x - myLoc.x;
		p.y = p.y + cLoc.y - myLoc.y;
	}

	private class DockableComponentDropTarget extends DropTarget {

		DockableComponentDropTarget(Component comp) {
			super(comp, null);
		}

		/**
		 * 
		 * @see java.awt.dnd.DropTargetListener#drop(java.awt.dnd.DropTargetDropEvent)
		 */
		@Override
		public synchronized void drop(DropTargetDropEvent dtde) {
			clearAutoscroll();
			if (dtde.isDataFlavorSupported(ComponentTransferable.localComponentProviderFlavor)) {
				Point p = dtde.getLocation();
				translate(p, ((DropTarget) dtde.getSource()).getComponent());
				setDropCode(p);
				TARGET_INFO = componentInfo;
				dtde.acceptDrop(dtde.getDropAction());
				dtde.dropComplete(true);
			}
			else {
				dtde.rejectDrop();
			}
		}

		/**
		 * 
		 * @see java.awt.dnd.DropTargetListener#dragEnter(java.awt.dnd.DropTargetDragEvent)
		 */
		@Override
		public synchronized void dragEnter(DropTargetDragEvent dtde) {
			super.dragEnter(dtde);

			// On Mac, sometimes this component is not showing, 
			// which causes exception in the translate method.
			if (!isShowing()) {
				dtde.rejectDrag();
				return;
			}

			if (dtde.isDataFlavorSupported(ComponentTransferable.localComponentProviderFlavor)) {
				Point p = dtde.getLocation();
				translate(p, ((DropTarget) dtde.getSource()).getComponent());
				setDropCode(p);
				DRAGGED_OVER_INFO = componentInfo;
				dtde.acceptDrag(dtde.getDropAction());
			}
			else {
				dtde.rejectDrag();
			}
		}

		/**
		 * 
		 * @see java.awt.dnd.DropTargetListener#dragOver(java.awt.dnd.DropTargetDragEvent)
		 */
		@Override
		public synchronized void dragOver(DropTargetDragEvent dtde) {
			super.dragOver(dtde);

			// On Mac, sometimes this component is not showing, 
			// which causes exception in the translate method.
			if (!isShowing()) {
				dtde.rejectDrag();
				return;
			}

			if (dtde.isDataFlavorSupported(ComponentTransferable.localComponentProviderFlavor)) {
				Point p = dtde.getLocation();
				translate(p, ((DropTarget) dtde.getSource()).getComponent());
				setDropCode(p);
				DRAGGED_OVER_INFO = componentInfo;
				dtde.acceptDrag(dtde.getDropAction());
			}
			else {
				dtde.rejectDrag();
			}
		}

		/**
		 * 
		 * @see java.awt.dnd.DropTargetListener#dragExit(java.awt.dnd.DropTargetEvent)
		 */
		@Override
		public synchronized void dragExit(DropTargetEvent dte) {
			super.dragExit(dte);
			DROP_CODE = DropCode.WINDOW;
			DROP_CODE_SET = true;
			DRAGGED_OVER_INFO = null;
		}

	}

	public void installDragDropTarget(Component component) {
		new DockableComponentDropTarget(component);
	}

	private void initializeComponents(Component comp) {
		if (comp instanceof CellRendererPane) {
			return;
		}
		if (comp instanceof Container) {
			Container c = (Container) comp;
			c.addContainerListener(this);
			Component comps[] = c.getComponents();
			for (Component comp2 : comps) {
				initializeComponents(comp2);
			}
		}
		DropTarget dt = comp.getDropTarget();
		if (dt != null) {
			new CascadedDropTarget(comp, dockableDropTarget, dt);
		}

		if (comp.isFocusable()) {
			comp.removeMouseListener(popupListener);
			comp.addMouseListener(popupListener);
		}
	}

	private void deinitializeComponents(Component comp) {
		if (comp instanceof CellRendererPane) {
			return;
		}
		if (comp instanceof Container) {
			Container c = (Container) comp;
			c.removeContainerListener(this);
			Component comps[] = c.getComponents();
			for (Component comp2 : comps) {
				deinitializeComponents(comp2);
			}
		}
		DropTarget dt = comp.getDropTarget();
		if (dt instanceof CascadedDropTarget) {
			CascadedDropTarget cascadedDropTarget = (CascadedDropTarget) dt;
			DropTarget newDropTarget = cascadedDropTarget.removeDropTarget(dockableDropTarget);
			comp.setDropTarget(newDropTarget);
		}
		comp.removeMouseListener(popupListener);
	}

	/**
	 * Sets the drop code base on the cursor location.
	 * @param p the cursor location.
	 */
	private void setDropCode(Point p) {
		DROP_CODE_SET = true;

		if (componentInfo == null) {
			DROP_CODE = DropCode.ROOT;
			return;
		}
		if (SOURCE_INFO == null) {
			DROP_CODE = DropCode.WINDOW;
			return;
		}
		if (SOURCE_INFO.getNode().winMgr != componentInfo.getNode().winMgr) {
			DROP_CODE = DropCode.WINDOW;
			return;
		}
		if (SOURCE_INFO == componentInfo && !componentInfo.isStacked()) {
			DROP_CODE = DropCode.INVALID;
			return;
		}
		else if (p.x < DROP_EDGE_OFFSET) {
			DROP_CODE = DropCode.LEFT;
		}
		else if (p.x > getWidth() - DROP_EDGE_OFFSET) {
			DROP_CODE = DropCode.RIGHT;
		}
		else if (p.y < DROP_EDGE_OFFSET) {
			DROP_CODE = DropCode.TOP;
		}
		else if (p.y > getHeight() - DROP_EDGE_OFFSET) {
			DROP_CODE = DropCode.BOTTOM;
		}
		else if (SOURCE_INFO == componentInfo) {
			DROP_CODE = DropCode.INVALID;
		}
		else {
			DROP_CODE = DropCode.STACK;
		}
	}

	void setSelected(boolean selected) {
		header.setSelected(selected);
	}

	/**
	 * Signals to use the GUI to make this component stand out from the rest.
	 */
	void emphasize() {
		header.emphasize();
	}

	/**
	 * Set the title displayed in this component's header.
	 * @param title
	 */
	void setTitle(String title) {
		header.setTitle(title);
	}

	void setIcon(Icon icon) {
		header.setIcon(icon);
	}

	/**
	 * Releases all resources for this object.
	 */
	void dispose() {
		header.dispose();
		header = null;
		componentInfo = null;
		providerComp = null;
		actionMgr = null;
	}

	/**
	 * Notifies the header that an action was added.
	 * @param action the action that was added.
	 */
	void actionAdded(DockingActionIf action) {
		header.actionAdded(action);
	}

	/**
	 * Notifies the header that an action was removed.
	 * @param action the action that was removed.
	 */
	void actionRemoved(DockingActionIf action) {
		header.actionRemoved(action);
	}

	@Override
	// we aren't focusable, so pass focus to a valid child component
	public void requestFocus() {
		focusedComponent = findFocusedComponent();
		if (focusedComponent != null) {
			DockingWindowManager.requestFocus(focusedComponent);
		}
	}

	void setFocusedComponent(Component newFocusedComponet) {
		// remember it so we can restore it later when necessary
		focusedComponent = newFocusedComponet;
	}

	private void componentSelected(Component component) {
		if (!component.isFocusable()) {
			// In this case, Java will not change focus for us, so we need to tell the DWM to 
			// change the active DockableComponent
			requestFocus();
		}
	}

	// find the first available component that can take focus
	private Component findFocusedComponent() {
		if (focusedComponent != null && focusedComponent.isShowing()) {
			return focusedComponent;
		}

		DefaultFocusManager dfm = (DefaultFocusManager) FocusManager.getCurrentManager();
		Component component = dfm.getComponentAfter(this, this);

		// component must be a child of this DockableComponent
		if (component != null && SwingUtilities.isDescendingFrom(component, this)) {
			return component;
		}
		return null;
	}

	/**
	 * @see java.awt.event.ContainerListener#componentAdded(java.awt.event.ContainerEvent)
	 */
	@Override
	public void componentAdded(ContainerEvent e) {
		initializeComponents(e.getChild());
	}

	/**
	 * @see java.awt.event.ContainerListener#componentRemoved(java.awt.event.ContainerEvent)
	 */
	@Override
	public void componentRemoved(ContainerEvent e) {
		deinitializeComponents(e.getChild());
	}
}
