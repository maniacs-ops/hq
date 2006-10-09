/*
 * COPYRIGHT.  ZUHLKE ENGINEERING LIMITED 2005.  ALL RIGHTS RESERVED.
 * 
 * This software is provided by the copyright holder "as is" and any express 
 * or implied warranties, including, but not limited to, the implied warranties
 * of merchantability and fitness for a particular purpose are disclaimed. In 
 * no event shall Zuhlke Engineering Limited be liable for any direct, indirect, 
 * incidental, special, exemplary, or consequential damages (including, but not 
 * limited to, procurement of substitute goods or services; loss of use, data, 
 * or profits; or business interruption) however caused and on any theory of 
 * liability, whether in contract, strict liability, or tort (including 
 * negligence or otherwise) arising in any way out of the use of this
 * software, even if advised of the possibility of such damage. 
 */
package org.hyperic;

import com.mockrunner.mock.ejb.MockUserTransaction;
import org.mockejb.MDBDescriptor;
import org.mockejb.MockContainer;
import org.mockejb.OptionalCactusTestCase;
import org.mockejb.SessionBeanDescriptor;
import org.mockejb.jms.MockQueue;
import org.mockejb.jms.QueueConnectionFactoryImpl;
import org.mockejb.jndi.MockContextFactory;
import org.postgresql.jdbc2.optional.SimpleDataSource;

import javax.jms.MessageListener;
import javax.jms.Queue;
import javax.jms.QueueConnectionFactory;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

/**
 * Abstract base class for all JUnit tests that use Mock EJB
 * to test EJBs.  Factors out common features to allow them to
 * be reused consistently.
 *
 * Modified to be used within the mock jta context for HQ unit testing.
 * Be sure to override the <code>hibernate.transaction.manager_lookup_class</code>
 * by setting to an empty string in your <code>${user.home}/.hq/build.properties</code>
 *
 * In addition you must also add the following line to the above build.properties
 *
 * <code>hq.jta.UserTransaction=javax.transaction.UserTransaction</code>
 * 
 * @author Eoin Woods
 * @author Young Lee
 */
public abstract class MockBeanTestBase extends OptionalCactusTestCase
{
    private Context	context ;
    private MockContainer container ;
    private QueueConnectionFactory qcf ;
    private Queue queue ;

    // override these in the subclass
    private String database = "hq";
    private String username = "hq";
    private String password = "hq";
    private String server = "localhost";

    public MockBeanTestBase( String testName ){
        super(testName );
    }
    
    public MockBeanTestBase() {
        super("MockBeanTest");
    }

    public void setDatabase(String database)
    {
        this.database = database;
    }

    public void setUsername(String username)
    {
        this.username = username;
    }

    public void setPassword(String password)
    {
        this.password = password;
    }

    public void setServer(String server)
    {
        this.server = server;
    }

    /**
     * Perform pre-test initialisation
     * @throws Exception if the initialisation fails
     */
    public void setUp() throws Exception
    {
        initialiseContainer() ;
    }
    
    /**
     * Performs the necessary cleanup by restoring the system properties that
     * were modified by MockContextFactory.setAsInitial().
     * This is needed in case if the test runs inside the container, so it would
     * not affect the tests that run after it.  
     */
    public void tearDown() {
        
        // Inside the container this method does not do anything
        MockContextFactory.revertSetAsInitial();
    }
    
    /**
     * Initialising the context and mock container
     * @throws NamingException if the initialisation cannot be completed
     */
    public void initialiseContainer() throws NamingException
    {
       
        
        /* We want to use MockEJB JNDI provider only if we run outside of container.
         * Inside container we want to rely on the "real" JNDI provided by that
         * container. 
         */
        if ( ! isRunningOnServer() ) {            
            /* We need to set MockContextFactory as our JNDI provider.
             * This method sets the necessary system properties. 
             */
            MockContextFactory.setAsInitial();
        }
        // create the initial context that will be used for binding EJBs
        this.context = new InitialContext();
        
        // Create an instance of the MockContainer
        this.container = new MockContainer(context);

        // bind jta transaction
        // we use MockTransaction outside of the app server
        MockUserTransaction mockTransaction = new MockUserTransaction();
        context.rebind("javax.transaction.UserTransaction", mockTransaction );

        // bind datasource (we use postgres in test environ)
        SimpleDataSource ds = new SimpleDataSource();
        ds.setDatabaseName(database);
        ds.setUser(username);
        ds.setPassword(password);
        ds.setServerName(server);
        context.rebind("java:/HypericDS", ds);
    }

   /**
    * Deploy the session bean that has the specified remote interface
    * class.  The bean must have a remote interface and must follow the
    * naming convention "Service", "ServiceHome", "ServiceBean" for the
    * remote, home and bean classes respectively.
    * 
    * @param jndiName the JNDI name to deploy the bean under (e.g. "ejb/Bean1")
    * @param beanInterfaceName the fully qualified Java class name of the
    *        bean's remote interface (e.g. "com.foo.beans.BeanOne")
    * @throws Exception for any failure
    */
    public void deployRemoteSessionBean(String jndiName, String beanInterfaceName) 
    	throws Exception
    {
        // if the test runs outside of the container
        if (! isRunningOnServer()) {
        
            /* Create deployment descriptor of our sample bean.
             * MockEjb uses it instead of XML deployment descriptors
             */
            ClassLoader ldr = this.getClass().getClassLoader() ;
            Class homeClass = ldr.loadClass(beanInterfaceName + "Home");
            Class remoteClass = ldr.loadClass(beanInterfaceName);
            Class beanClass = ldr.loadClass(beanInterfaceName + "Bean");
            Object bean = beanClass.newInstance() ;
            SessionBeanDescriptor sampleServiceDescriptor = 
                new SessionBeanDescriptor(jndiName, homeClass, remoteClass, bean) ;
            // Deploy operation creates Home and binds it to JNDI
            this.container.deploy(sampleServiceDescriptor);
        }

    }
    
    /**
     * Deploy an MDB attached to a queue to the mock container
     * @param factoryName the name of the queue connection factory containing 
     *        the queue
     * @param responseQueueName the name of the queue to attach the bean to
     * @param beanClassName the full classname of the bean to deploy
     * @throws Exception if the deployment cannot be completed
     */
    public void deployQueueMessageDrivenBean(String factoryName, 
            String requestQueueName, String responseQueueName, String beanClassName) throws Exception
    {
        // if the test runs outside of the container
        if (! isRunningOnServer()) {
 
            ClassLoader ldr = this.getClass().getClassLoader() ;
            Class beanClass = ldr.loadClass(beanClassName);
            Object beanObj = beanClass.newInstance() ;
    
            this.qcf = new QueueConnectionFactoryImpl() ;
            this.context.rebind(factoryName, this.qcf);
            
            this.queue = new MockQueue(requestQueueName) ;
            ((MockQueue)this.queue).addMessageListener((MessageListener)beanObj) ;
            this.context.rebind(requestQueueName, this.queue);
            
            this.context.rebind(responseQueueName, new MockQueue(responseQueueName));
           
            MDBDescriptor mdbDescriptor = 
                new MDBDescriptor(factoryName, requestQueueName, beanObj);
            mdbDescriptor.setIsAlreadyBound(true) ;
            // This will create connection factory and destination, create MDB and set
            // it as the listener to the destination
            this.container.deploy( mdbDescriptor );
        }
    }
    
    /**
     * Return the context object that should be used to locate resources
     * in the J2EE container.
     * @return a context object
     */
    public Context getContext()
    {
        return this.context ;
    }
    
    public QueueConnectionFactory getCurrentQCF()
    {
        return this.qcf ;
    }
    
    public Queue getCurrentQueue()
    {
        return this.queue ;
    }
}
